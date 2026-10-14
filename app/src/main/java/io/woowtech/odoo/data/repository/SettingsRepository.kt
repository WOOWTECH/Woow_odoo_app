package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.local.PersistedPinLockout
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import io.woowtech.odoo.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class SettingsRepository @Inject constructor(
    private val encryptedPrefs: EncryptedPrefs,
    private val appLockClock: AppLockClock = AppLockClock(),
) {
    private val _settings = MutableStateFlow(loadInitialSettings())
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

    /**
     * Updates the App Lock preference, enforcing the invariant `appLockEnabled ⇒ pinEnabled`.
     *
     * App Lock cannot be turned ON unless a PIN is already set: the PIN is the guaranteed
     * unlock floor (a biometric is only an optional accelerator and can become unavailable
     * at runtime — e.g. a weak/Class-2 face, or an enrollment removed after enabling). Arming
     * App Lock with no working method is exactly the dead-end this guard prevents; the Settings
     * UI must run PIN setup first. This is defense-in-depth behind that UI gate.
     *
     * Disabling App Lock is always allowed and never requires authentication.
     *
     * @return `true` if the requested state was applied; `false` if an ON request was refused
     *   because no PIN is set (persisted state left unchanged).
     */
    fun updateAppLock(enabled: Boolean): Boolean {
        if (enabled && !_settings.value.pinEnabled) {
            Timber.w("updateAppLock(true) refused — no PIN set; App Lock requires a PIN floor")
            return false
        }
        encryptedPrefs.updateAppLock(enabled)
        _settings.value = _settings.value.copy(appLockEnabled = enabled)
        return true
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

    /**
     * Hashes the given [pin] with PBKDF2 (600,000 iterations) and persists the result.
     *
     * The PBKDF2 computation is dispatched to [Dispatchers.Default] so it never
     * executes on the main thread. Callers must launch this in a coroutine scope
     * (e.g. [viewModelScope] in a ViewModel or [lifecycleScope] in an Activity).
     *
     * @return `true` on success, `false` when [pin] length is outside 4–6.
     */
    suspend fun setPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        if (pin.length != PIN_LENGTH) return@withContext false

        val hash = hashPin(pin)
        encryptedPrefs.updatePinHash(hash)
        _settings.value = _settings.value.copy(
            pinEnabled = true,
            pinHash = hash
        )
        true
    }

    /**
     * Removes the stored PIN and, atomically in the same write, disables App Lock.
     *
     * Because the PIN is the mandatory unlock floor, removing it while App Lock stayed ON
     * would re-create the dead-end this story fixes. Both changes are persisted through a
     * single [EncryptedPrefs.saveAppSettings] commit, so a crash between the two can never
     * leave `appLockEnabled=true && pinEnabled=false` on disk.
     */
    fun removePin() {
        val newSettings = _settings.value.copy(
            pinEnabled = false,
            pinHash = null,
            appLockEnabled = false, // co-disable atomically — never leave App Lock armed without a PIN
            failedPinAttempts = 0,
            pinLockoutUntil = null,
        )
        encryptedPrefs.saveAppSettings(newSettings)
        _settings.value = newSettings
    }

    /**
     * Resets the persisted failed-PIN-attempt counter. Used by the auth flow on
     * successful unlock and by debug test hooks to clear lockout state between runs.
     */
    fun resetFailedPinAttempts() {
        encryptedPrefs.resetFailedPinAttempts()
        _settings.value = _settings.value.copy(
            failedPinAttempts = 0,
            pinLockoutUntil = null,
        )
    }

    /**
     * Verifies [pin] against the stored PBKDF2 hash (600,000 iterations).
     *
     * The PBKDF2 computation is dispatched to [Dispatchers.Default] so it never
     * blocks the main thread. The inherent verify cost is still 1–3 seconds on
     * mid-range hardware; callers should display a progress indicator while
     * suspended (see [PinScreen]'s `isVerifying` state).
     *
     * Also handles the legacy SHA-256 → PBKDF2 migration path: if the stored hash
     * has no colon separator it is treated as a legacy hash and re-hashed with
     * PBKDF2 on successful verification (also off the main thread).
     *
     * Increments the persisted failed-attempt counter and applies exponential
     * lockout on failure. Resets the counter on success.
     *
     * @return `true` when [pin] matches the stored hash and the account is not
     *   locked out, `false` otherwise.
     */
    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val currentHash = _settings.value.pinHash ?: return@withContext false

        if (getLockoutRemainingMs() > 0L) return@withContext false

        val matches = verifyPbkdf2(pin, currentHash)
        if (matches) {
            resetFailedPinAttempts()
            true
        } else {
            val attempts = encryptedPrefs.incrementFailedPinAttempts()
            if (attempts >= MAX_PIN_ATTEMPTS) {
                val lockoutDuration = getLockoutDuration(attempts)
                val now = appLockClock.now()
                val lockoutUntil = saturatingAdd(now.elapsedRealtimeMs, lockoutDuration)
                persistLockout(now, lockoutDuration)
                _settings.value = _settings.value.copy(
                    failedPinAttempts = attempts,
                    pinLockoutUntil = lockoutUntil,
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
        return getLockoutRemainingMs() > 0L
    }

    fun getLockoutRemainingMs(): Long {
        val lockoutUntil = _settings.value.pinLockoutUntil ?: return 0
        val now = appLockClock.now()
        val tierCap = lockoutDurationCap(_settings.value.failedPinAttempts)
        val remaining = positiveDifference(lockoutUntil, now.elapsedRealtimeMs)
            .coerceAtMost(tierCap)

        if (remaining == 0L) clearExpiredLockout()
        return remaining
    }

    /**
     * Loads and canonicalises the persisted lockout before exposing settings to the UI.
     *
     * Same-boot restarts use only elapsed realtime. After a reboot, the wall deadline is used once
     * and immediately rebased onto the new monotonic epoch. Every recovered value is capped by both
     * its saved remaining budget and the lockout tier implied by the persisted failure count.
     */
    private fun loadInitialSettings(): AppSettings {
        val stored = encryptedPrefs.getAppSettings()
        val persisted = encryptedPrefs.getPinLockout()
        val legacyDeadline = stored.pinLockoutUntil

        if (persisted == null && legacyDeadline == null) return stored

        val tierCap = lockoutDurationCap(stored.failedPinAttempts)
        // No lockout tier means any record is stale/corrupt. Clear it without consulting the
        // Android monotonic clock; ordinary settings reads should not need a platform clock at all.
        if (tierCap == 0L) {
            encryptedPrefs.clearPinLockout()
            return stored.copy(pinLockoutUntil = null)
        }

        val now = appLockClock.now()
        val remaining = if (persisted != null) {
            resolvePersistedLockout(persisted, now, tierCap)
        } else {
            resolveLegacyLockout(legacyDeadline, now, tierCap)
        }

        if (remaining == 0L) {
            encryptedPrefs.clearPinLockout()
            return stored.copy(pinLockoutUntil = null)
        }

        // This also migrates the old v0 elapsed-only value and rebases a cross-boot record.
        persistLockout(now, remaining)
        return stored.copy(pinLockoutUntil = saturatingAdd(now.elapsedRealtimeMs, remaining))
    }

    private fun resolvePersistedLockout(
        persisted: PersistedPinLockout,
        now: AppLockTime,
        tierCap: Long,
    ): Long {
        val savedBudget = persisted.remainingBudgetMs.coerceIn(0L, tierCap)
        if (savedBudget == 0L) return 0L

        val rawRemaining = if (persisted.bootId == now.bootId) {
            // User wall-clock edits are irrelevant for the entire current boot.
            positiveDifference(persisted.elapsedDeadlineMs, now.elapsedRealtimeMs)
        } else {
            resolveCrossBootWallTime(persisted, now, savedBudget)
        }
        return rawRemaining.coerceAtMost(savedBudget)
    }

    private fun resolveCrossBootWallTime(
        persisted: PersistedPinLockout,
        now: AppLockTime,
        savedBudget: Long,
    ): Long {
        // Recover the wall sample at which this canonical record was written. A valid elapsed wall
        // interval must be non-negative and at least the new boot's current uptime (the record was
        // necessarily written before this boot began).
        val savedWallClock = persisted.wallDeadlineMs - savedBudget
        val wallElapsed = if (
            savedWallClock >= 0L &&
            now.wallClockMs >= savedWallClock &&
            now.elapsedRealtimeMs >= 0L
        ) {
            now.wallClockMs - savedWallClock
        } else {
            -1L
        }
        val plausibleUpperBound = saturatingAdd(savedBudget, MAX_PLAUSIBLE_WALL_OVERRUN_MS)
        val wallIsImplausible = savedWallClock < 0L ||
            wallElapsed < now.elapsedRealtimeMs ||
            wallElapsed > plausibleUpperBound

        if (wallIsImplausible) {
            // A manual rollback or large forward jump must not bypass App Lock. Requiring at most the
            // saved budget is conservative but bounded (one hour maximum), then the new monotonic
            // deadline counts down normally so this cannot become a permanent lockout.
            return savedBudget
        }

        // A plausible reboot interval counts against the lockout, including an ordinary reboot that
        // took slightly longer than the remaining duration.
        return (savedBudget - wallElapsed).coerceAtLeast(0L)
    }

    private fun resolveLegacyLockout(
        legacyDeadline: Long?,
        now: AppLockTime,
        tierCap: Long,
    ): Long {
        val deadline = legacyDeadline ?: return 0L
        // v0 has no boot ID. Only a deadline plausible on the current monotonic epoch is retained;
        // a stale post-reboot value larger than its tier is cleared rather than locking indefinitely.
        val rawRemaining = positiveDifference(deadline, now.elapsedRealtimeMs)
        return rawRemaining.takeIf { it in 1L..tierCap } ?: 0L
    }

    private fun persistLockout(now: AppLockTime, remainingMs: Long) {
        val boundedRemaining = remainingMs
            .coerceAtLeast(0L)
            .coerceAtMost(MAX_LOCKOUT_DURATION_MS)
        if (boundedRemaining == 0L) {
            encryptedPrefs.clearPinLockout()
            return
        }

        encryptedPrefs.setPinLockout(
            PersistedPinLockout(
                bootId = now.bootId,
                elapsedDeadlineMs = saturatingAdd(now.elapsedRealtimeMs, boundedRemaining),
                wallDeadlineMs = saturatingAdd(now.wallClockMs, boundedRemaining),
                remainingBudgetMs = boundedRemaining,
            )
        )
    }

    private fun clearExpiredLockout() {
        encryptedPrefs.clearPinLockout()
        if (_settings.value.pinLockoutUntil != null) {
            _settings.value = _settings.value.copy(pinLockoutUntil = null)
        }
    }

    private fun lockoutDurationCap(failedAttempts: Int): Long {
        if (failedAttempts < MAX_PIN_ATTEMPTS) return 0L
        return getLockoutDuration(failedAttempts).coerceAtMost(MAX_LOCKOUT_DURATION_MS)
    }

    private fun positiveDifference(deadline: Long, now: Long): Long {
        if (deadline <= now || deadline < 0L || now < 0L) return 0L
        return deadline - now
    }

    private fun saturatingAdd(base: Long, duration: Long): Long {
        if (base < 0L || duration <= 0L) return base.coerceAtLeast(0L)
        return if (base > Long.MAX_VALUE - duration) Long.MAX_VALUE else base + duration
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
     * Persists and reflects the user's preference for whether the app may share
     * the device location with the active Odoo server during attendance clock-in.
     * This is a user-facing opt-out; the default is true (enabled).
     */
    fun updateLocationEnabled(enabled: Boolean) {
        encryptedPrefs.updateLocationEnabled(enabled)
        _settings.value = _settings.value.copy(locationEnabled = enabled)
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
        /**
         * Fixed PIN length. PINs are exactly 6 digits (no 4–6 flexibility) — the single source of
         * truth for PIN length, owned by the data layer. The PIN is verified exactly once, at 6
         * digits (see [io.woowtech.odoo.ui.auth.AuthViewModel.enterPinDigit]).
         */
        const val PIN_LENGTH = 6

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
        private const val MAX_LOCKOUT_DURATION_MS = 3_600_000L
        // Allows normal reboot/shutdown overhead past an expiry while treating larger RTC jumps as
        // untrusted. An untrusted value falls back to the bounded saved budget, never an unbounded date.
        private const val MAX_PLAUSIBLE_WALL_OVERRUN_MS = 5 * 60 * 1_000L

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
