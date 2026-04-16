package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import io.woowtech.odoo.ui.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

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
     * Updates the biometric-unlock preference.
     *
     * When [canEnable] is false (caller has confirmed that [androidx.biometric.BiometricManager]
     * reports no available strong biometric), the setting is forced to false regardless of the
     * requested [enabled] value, and a warning is logged. This prevents the UI from enabling
     * biometric unlock on devices where it cannot function, which would silently break
     * authentication. (M1 fix)
     *
     * @param enabled the desired state.
     * @param canEnable pass [androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS] == true;
     *   defaults to `true` so existing callers that pre-check availability are unaffected.
     */
    fun updateBiometric(enabled: Boolean, canEnable: Boolean = true) {
        val effective = enabled && canEnable
        if (enabled && !canEnable) {
            Timber.w("updateBiometric(true) rejected — BiometricManager reports unavailable, forcing false")
        }
        encryptedPrefs.updateBiometric(effective)
        _settings.value = _settings.value.copy(biometricEnabled = effective)
    }

    fun setPin(pin: String): Boolean {
        if (pin.length < 4 || pin.length > 6) return false

        val hash = hashPin(pin)
        encryptedPrefs.updatePinHash(hash)
        _settings.value = _settings.value.copy(
            pinEnabled = true,
            pinHash = hash
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

    fun verifyPin(pin: String): Boolean {
        val currentHash = _settings.value.pinHash ?: return false

        // Check if locked out
        _settings.value.pinLockoutUntil?.let { lockoutUntil ->
            if (SystemClock.elapsedRealtime() < lockoutUntil) {
                return false
            }
        }

        val matches = verifyPbkdf2(pin, currentHash)
        return if (matches) {
            encryptedPrefs.resetFailedPinAttempts()
            _settings.value = _settings.value.copy(
                failedPinAttempts = 0,
                pinLockoutUntil = null
            )
            true
        } else {
            val attempts = encryptedPrefs.incrementFailedPinAttempts()
            if (attempts >= MAX_PIN_ATTEMPTS) {
                val lockoutDuration = getLockoutDuration(attempts)
                val lockoutUntil = SystemClock.elapsedRealtime() + lockoutDuration
                encryptedPrefs.setPinLockout(lockoutUntil)
                _settings.value = _settings.value.copy(
                    failedPinAttempts = attempts,
                    pinLockoutUntil = lockoutUntil
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

    fun isLockedOut(): Boolean {
        val lockoutUntil = _settings.value.pinLockoutUntil ?: return false
        return SystemClock.elapsedRealtime() < lockoutUntil
    }

    fun getLockoutRemainingMs(): Long {
        val lockoutUntil = _settings.value.pinLockoutUntil ?: return 0
        return maxOf(0, lockoutUntil - SystemClock.elapsedRealtime())
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

    /**
     * Hashes a PIN using PBKDF2WithHmacSHA256 with a random 16-byte salt.
     * Returns the salt and hash encoded as "salt:hash" in hex.
     */
    private fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2Hash(pin, salt)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        return "$saltHex:$hashHex"
    }

    /**
     * Verifies a PIN against a stored "salt:hash" string.
     * Also supports legacy unsalted SHA-256 hashes for migration.
     */
    private fun verifyPbkdf2(pin: String, stored: String): Boolean {
        if (!stored.contains(":")) {
            // Legacy SHA-256 hash — migrate on successful verify
            val legacyHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray())
                .joinToString("") { "%02x".format(it) }
            if (legacyHash == stored) {
                // Re-hash with PBKDF2 for future verifications
                val newHash = hashPin(pin)
                encryptedPrefs.updatePinHash(newHash)
                _settings.value = _settings.value.copy(pinHash = newHash)
                return true
            }
            return false
        }

        val parts = stored.split(":")
        if (parts.size != 2) return false

        val salt = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val expectedHash = parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val actualHash = pbkdf2Hash(pin, salt)
        return actualHash.contentEquals(expectedHash)
    }

    private fun pbkdf2Hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    companion object {
        private const val MAX_PIN_ATTEMPTS = 5
        private const val SALT_LENGTH_BYTES = 16
        private const val PBKDF2_ITERATIONS = 600_000
        private const val HASH_LENGTH_BITS = 256

        // Exponential lockout durations: 30s → 5min → 30min → 1hr
        private val LOCKOUT_DURATIONS_MS = longArrayOf(
            30_000L,       // 30 seconds (5 failures)
            300_000L,      // 5 minutes (10 failures)
            1_800_000L,    // 30 minutes (15 failures)
            3_600_000L,    // 1 hour (20+ failures)
        )

        /**
         * Returns lockout duration based on cumulative failed attempts.
         * Escalates: 30s → 5min → 30min → 1hr (caps at 1hr).
         */
        fun getLockoutDuration(failedAttempts: Int): Long {
            val lockoutIndex = (failedAttempts / MAX_PIN_ATTEMPTS) - 1
            return LOCKOUT_DURATIONS_MS[lockoutIndex.coerceIn(0, LOCKOUT_DURATIONS_MS.lastIndex)]
        }
    }
}
