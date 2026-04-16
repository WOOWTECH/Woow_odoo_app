package io.woowtech.odoo.data.repository

import android.os.SystemClock
import android.util.Log
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import io.woowtech.odoo.ui.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for application settings and PIN/lockout state.
 *
 * PIN hashing uses PBKDF2-HMAC-SHA256 with a per-PIN random salt (OWASP MASVS-STORAGE-1).
 * Legacy SHA-256 hashes (stored without a prefix) are automatically migrated on the next
 * successful PIN verify: the plain hash is verified once, then the PIN is re-hashed with
 * PBKDF2 and the old hash is overwritten. Failed verifications against a legacy hash leave
 * the hash unchanged so the migration does not consume a failure attempt.
 *
 * Lockout timing uses [SystemClock.elapsedRealtime] rather than wall-clock time
 * ([System.currentTimeMillis]). Wall-clock time is user-adjustable and can be manipulated
 * to bypass a lockout by setting the clock forward. `elapsedRealtime` is monotonic within
 * a single boot and cannot be set by the user. The accepted trade-off is that a device
 * reboot resets the lockout (elapsedRealtime resets to 0 on boot) — this is preferable to
 * a bypassable lockout.
 *
 * Lockout duration grows exponentially with the number of failures to deter brute force:
 * - 0–4 failures: no lockout
 * - 5–9 failures: 30 seconds
 * - 10–14 failures: 5 minutes
 * - 15–19 failures: 30 minutes
 * - 20+ failures: 1 hour
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val encryptedPrefs: EncryptedPrefs
) {
    private val _settings = MutableStateFlow(encryptedPrefs.getAppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        // Apply saved theme color and mode on init
        ThemeManager.setPrimaryColorFromHex(_settings.value.themeColor)
        ThemeManager.setThemeMode(_settings.value.themeMode)
    }

    fun updateThemeColor(color: String) {
        encryptedPrefs.updateThemeColor(color)
        ThemeManager.setPrimaryColorFromHex(color)
        _settings.value = _settings.value.copy(themeColor = color)
    }

    fun updateReduceMotion(enabled: Boolean) {
        val newSettings = _settings.value.copy(reduceMotion = enabled)
        encryptedPrefs.saveAppSettings(newSettings)
        _settings.value = newSettings
    }

    fun updateAppLock(enabled: Boolean) {
        encryptedPrefs.updateAppLock(enabled)
        _settings.value = _settings.value.copy(appLockEnabled = enabled)
    }

    /**
     * Persists the biometric-unlock preference.
     *
     * [canEnable] is a caller-supplied gate representing the current
     * [androidx.biometric.BiometricManager.canAuthenticate] result. When [enabled] is
     * `true` but [canEnable] is `false` (device capability lost after the previous save),
     * the effective value is forced to `false` and a warning is logged. This prevents the
     * setting from being stuck as `true` on devices that have since removed biometric
     * enrollment or experienced a hardware fault.
     *
     * Passing `canEnable = true` (the default) preserves the old behaviour so existing
     * call sites that already validate capability before calling this method are unaffected.
     *
     * M1 fix: previously the flag was written unconditionally without re-checking capability.
     */
    fun updateBiometric(enabled: Boolean, canEnable: Boolean = true) {
        val effective = enabled && canEnable
        if (enabled && !canEnable) {
            Log.w(TAG, "updateBiometric(true) rejected — BiometricManager reports unavailable, forcing false")
        }
        encryptedPrefs.updateBiometric(effective)
        _settings.value = _settings.value.copy(biometricEnabled = effective)
    }

    /**
     * Sets a new PIN. The PIN is hashed with PBKDF2-HMAC-SHA256 (600 000 iterations, 16-byte
     * random salt) and stored in the format `"pbkdf2:$iterations:$base64Salt:$base64Hash"`.
     *
     * @return `true` if the PIN was accepted and stored; `false` if the PIN length is outside
     *   the valid range [4, 6].
     */
    fun setPin(pin: String): Boolean {
        if (pin.length < MIN_PIN_LENGTH_ALLOWED || pin.length > MAX_PIN_LENGTH_ALLOWED) return false

        val salt = generateSalt()
        val hash = hashPinPbkdf2(pin = pin, salt = salt)
        val encoded = encodePbkdf2Hash(
            iterations = PBKDF2_ITERATIONS,
            salt = salt,
            hash = hash
        )
        encryptedPrefs.updatePinHash(encoded)
        _settings.value = _settings.value.copy(
            pinEnabled = true,
            pinHash = encoded
        )
        return true
    }

    fun removePin() {
        encryptedPrefs.updatePinHash(null)
        _settings.value = _settings.value.copy(
            pinEnabled = false,
            pinHash = null
        )
    }

    /**
     * Verifies [pin] against the stored hash. Handles two storage formats:
     * - `"pbkdf2:..."` — current PBKDF2 format; compares in constant time.
     * - No prefix (legacy) — SHA-256 hex string from before the PBKDF2 migration. On a
     *   match the hash is transparently upgraded to PBKDF2 and overwritten. On a mismatch
     *   the hash is left unchanged (no failure count is incremented for the legacy comparison
     *   itself — the failure count is incremented at the call site in all mismatch cases).
     *
     * Lockout is checked using [SystemClock.elapsedRealtime] before any comparison is made.
     * If the account is locked out `false` is returned immediately without incrementing the
     * failure counter.
     *
     * @return `true` if the PIN matched and the session counter was reset; `false` otherwise.
     */
    fun verifyPin(pin: String): Boolean {
        val currentHash = _settings.value.pinHash ?: return false

        // Lockout check — use elapsedRealtime to prevent wall-clock manipulation. (H2)
        _settings.value.pinLockoutUntil?.let { lockoutEndElapsed ->
            if (SystemClock.elapsedRealtime() < lockoutEndElapsed) {
                return false
            }
        }

        val matched = when {
            currentHash.startsWith(PBKDF2_PREFIX) -> verifyPbkdf2(pin = pin, stored = currentHash)
            else -> verifyLegacySha256(pin = pin, stored = currentHash)
        }

        return if (matched) {
            // On a successful legacy match, migrate to PBKDF2 transparently.
            if (!currentHash.startsWith(PBKDF2_PREFIX)) {
                Log.d(TAG, "Migrating legacy SHA-256 PIN hash to PBKDF2")
                val salt = generateSalt()
                val newHash = encodePbkdf2Hash(
                    iterations = PBKDF2_ITERATIONS,
                    salt = salt,
                    hash = hashPinPbkdf2(pin = pin, salt = salt)
                )
                encryptedPrefs.updatePinHash(newHash)
                _settings.value = _settings.value.copy(pinHash = newHash)
            }

            encryptedPrefs.resetFailedPinAttempts()
            _settings.value = _settings.value.copy(
                failedPinAttempts = 0,
                pinLockoutUntil = null
            )
            true
        } else {
            val attempts = encryptedPrefs.incrementFailedPinAttempts()
            val lockoutDuration = getLockoutDuration(failureCount = attempts)
            if (lockoutDuration > 0) {
                val lockoutEndElapsed = SystemClock.elapsedRealtime() + lockoutDuration
                encryptedPrefs.setPinLockout(lockoutEndElapsed)
                _settings.value = _settings.value.copy(
                    failedPinAttempts = attempts,
                    pinLockoutUntil = lockoutEndElapsed
                )
            } else {
                _settings.value = _settings.value.copy(failedPinAttempts = attempts)
            }
            false
        }
    }

    fun getRemainingAttempts(): Int {
        return MAX_PIN_ATTEMPTS - _settings.value.failedPinAttempts
    }

    /**
     * Returns `true` if the PIN entry is currently locked out based on
     * [SystemClock.elapsedRealtime]. A reboot will reset `elapsedRealtime` to zero, which
     * effectively clears any active lockout — this is an intentional trade-off.
     */
    fun isLockedOut(): Boolean {
        val lockoutUntil = _settings.value.pinLockoutUntil ?: return false
        return SystemClock.elapsedRealtime() < lockoutUntil
    }

    /**
     * Returns the number of milliseconds remaining in the current lockout period, or 0 if
     * not currently locked out. Uses [SystemClock.elapsedRealtime] for monotonic timing.
     */
    fun getLockoutRemainingMs(): Long {
        val lockoutUntil = _settings.value.pinLockoutUntil ?: return 0
        return maxOf(0L, lockoutUntil - SystemClock.elapsedRealtime())
    }

    /**
     * Returns the lockout duration in milliseconds for the given cumulative [failureCount].
     * Duration increases exponentially at 5-attempt tiers to deter brute-force attacks
     * while keeping the first-tier penalty short enough to not lock out a legitimate user
     * who mistyped once. (H5)
     *
     * Tier table:
     * - 0–4 failures → 0 ms (no lockout)
     * - 5–9 failures → 30 seconds
     * - 10–14 failures → 5 minutes
     * - 15–19 failures → 30 minutes
     * - 20+ failures → 1 hour
     */
    fun getLockoutDuration(failureCount: Int): Long = when {
        failureCount < 5 -> 0L
        failureCount < 10 -> 30_000L          // 30 s
        failureCount < 15 -> 5 * 60_000L      // 5 min
        failureCount < 20 -> 30 * 60_000L     // 30 min
        else -> 60 * 60_000L                  // 1 h
    }

    fun updateLanguage(language: AppLanguage) {
        encryptedPrefs.updateLanguage(language)
        _settings.value = _settings.value.copy(language = language)
    }

    fun updateThemeMode(mode: ThemeMode) {
        encryptedPrefs.updateThemeMode(mode)
        ThemeManager.setThemeMode(mode)
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    // -------------------------------------------------------------------------
    // PIN hashing helpers
    // -------------------------------------------------------------------------

    private fun hashPinPbkdf2(
        pin: String,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): ByteArray {
        val spec = PBEKeySpec(
            pin.toCharArray(),
            salt,
            iterations,
            HASH_SIZE_BITS
        )
        return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
    }

    private fun encodePbkdf2Hash(iterations: Int, salt: ByteArray, hash: ByteArray): String {
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val hashB64 = Base64.getEncoder().encodeToString(hash)
        return "$PBKDF2_PREFIX$iterations:$saltB64:$hashB64"
    }

    /**
     * Verifies a PIN against a stored PBKDF2 hash in the format
     * `"pbkdf2:$iterations:$base64Salt:$base64Hash"`. Uses [MessageDigest.isEqual] for
     * constant-time comparison to prevent timing side-channel attacks.
     */
    private fun verifyPbkdf2(pin: String, stored: String): Boolean {
        return try {
            // Format: "pbkdf2:600000:<base64 salt>:<base64 hash>"
            val parts = stored.removePrefix(PBKDF2_PREFIX).split(":")
            if (parts.size != 3) return false
            val iterations = parts[0].toInt()
            val salt = Base64.getDecoder().decode(parts[1])
            val expectedHash = Base64.getDecoder().decode(parts[2])
            // Use the stored iteration count so that a future increase only affects
            // newly set PINs while existing hashes continue to verify.
            val actualHash = hashPinPbkdf2(pin = pin, salt = salt, iterations = iterations)
            MessageDigest.isEqual(actualHash, expectedHash)
        } catch (e: Exception) {
            Log.e(TAG, "PBKDF2 verification failed unexpectedly", e)
            false
        }
    }

    /**
     * Verifies a PIN against a legacy SHA-256 hex string (no prefix). Constant-time via
     * [MessageDigest.isEqual]. This path is only reached during the one-time migration
     * window; after a successful match the hash is re-stored as PBKDF2.
     */
    private fun verifyLegacySha256(pin: String, stored: String): Boolean {
        val inputBytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        val inputHex = inputBytes.joinToString("") { "%02x".format(it) }
        // Constant-time compare — convert both to byte arrays of the same length.
        return MessageDigest.isEqual(inputHex.toByteArray(), stored.toByteArray())
    }

    private fun generateSalt(): ByteArray {
        return ByteArray(SALT_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
    }

    companion object {
        private const val MAX_PIN_ATTEMPTS = 5
        private const val MIN_PIN_LENGTH_ALLOWED = 4
        private const val MAX_PIN_LENGTH_ALLOWED = 6

        // PBKDF2 parameters — aligned with OWASP recommendation of 600 000 iterations for
        // PBKDF2-HMAC-SHA256 as of 2023, and with the iOS PinHasher reference implementation.
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 600_000
        private const val SALT_SIZE_BYTES = 16
        private const val HASH_SIZE_BITS = 256
        private const val PBKDF2_PREFIX = "pbkdf2:"
        private const val TAG = "SettingsRepository"
    }
}
