package io.woowtech.odoo.data.local

import io.woowtech.odoo.brand.AppBrand
import android.content.Context
import android.content.SharedPreferences
import dev.spght.encryptedprefs.EncryptedSharedPreferences
import dev.spght.encryptedprefs.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Versioned App Lock lockout state.
 *
 * The elapsed deadline is safe only for [bootId]. The wall deadline is a reboot fallback and
 * [remainingBudgetMs] bounds it so a rolled-back or corrupt wall clock cannot create an unbounded
 * lockout.
 */
data class PersistedPinLockout(
    val bootId: String,
    val elapsedDeadlineMs: Long,
    val wallDeadlineMs: Long,
    val remainingBudgetMs: Long,
)

@Singleton
class EncryptedPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore corruption — delete the corrupted file and recreate
        context.getSharedPreferences("encrypted_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        val file = java.io.File(context.filesDir.parent, "shared_prefs/encrypted_prefs.xml")
        if (file.exists()) file.delete()
        EncryptedSharedPreferences.create(
            context,
            "encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Session storage (password for API calls)
    fun savePassword(accountId: String, password: String) {
        prefs.edit().putString("pwd_$accountId", password).apply()
    }

    fun getPassword(accountId: String): String? {
        return prefs.getString("pwd_$accountId", null)
    }

    fun removePassword(accountId: String) {
        prefs.edit().remove("pwd_$accountId").apply()
    }

    // App Settings
    fun saveAppSettings(settings: AppSettings) {
        prefs.edit().apply {
            putString(KEY_THEME_COLOR, settings.themeColor)
            putString(KEY_THEME_MODE, settings.themeMode.code)
            putBoolean(KEY_REDUCE_MOTION, settings.reduceMotion)
            putBoolean(KEY_APP_LOCK_ENABLED, settings.appLockEnabled)
            putBoolean(KEY_BIOMETRIC_ENABLED, settings.biometricEnabled)
            putBoolean(KEY_PIN_ENABLED, settings.pinEnabled)
            putString(KEY_PIN_HASH, settings.pinHash)
            putString(KEY_LANGUAGE, settings.language.code)
            if (settings.pinEnabled) {
                putInt(KEY_FAILED_PIN_ATTEMPTS, settings.failedPinAttempts)
            } else {
                // Removing the PIN is one atomic security-state transition: no stale retry tier or
                // persisted deadline may be inherited if the user creates a new PIN later.
                putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
                removePinLockoutKeys()
            }
            // Lockout time has its own versioned, multi-clock record. Never overwrite it with
            // AppSettings.pinLockoutUntil, which is only a process-local elapsedRealtime deadline.
            putBoolean(KEY_LOCATION_ENABLED, settings.locationEnabled)
        }.apply()
    }

    fun getAppSettings(): AppSettings {
        return AppSettings(
            themeColor = prefs.getString(KEY_THEME_COLOR, AppBrand.current.primaryHex) ?: AppBrand.current.primaryHex,
            themeMode = ThemeMode.entries.find {
                it.code == prefs.getString(KEY_THEME_MODE, "system")
            } ?: ThemeMode.SYSTEM,
            reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
            appLockEnabled = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false),
            biometricEnabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false),
            pinEnabled = prefs.getBoolean(KEY_PIN_ENABLED, false),
            pinHash = prefs.getString(KEY_PIN_HASH, null),
            language = AppLanguage.entries.find {
                it.code == prefs.getString(KEY_LANGUAGE, "system")
            } ?: AppLanguage.SYSTEM,
            failedPinAttempts = prefs.getInt(KEY_FAILED_PIN_ATTEMPTS, 0),
            pinLockoutUntil = if (prefs.contains(KEY_PIN_LOCKOUT_UNTIL)) {
                prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0)
            } else null,
            locationEnabled = prefs.getBoolean(KEY_LOCATION_ENABLED, true),
        )
    }

    fun updateThemeColor(color: String) {
        prefs.edit().putString(KEY_THEME_COLOR, color).apply()
    }

    fun updateAppLock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
    }

    fun updateBiometric(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun updatePinHash(hash: String?) {
        prefs.edit().apply {
            if (hash != null) {
                putString(KEY_PIN_HASH, hash)
                putBoolean(KEY_PIN_ENABLED, true)
            } else {
                remove(KEY_PIN_HASH)
                putBoolean(KEY_PIN_ENABLED, false)
            }
        }.apply()
    }

    fun incrementFailedPinAttempts(): Int {
        val attempts = prefs.getInt(KEY_FAILED_PIN_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_FAILED_PIN_ATTEMPTS, attempts).apply()
        return attempts
    }

    fun resetFailedPinAttempts() {
        prefs.edit().apply {
            putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
            removePinLockoutKeys()
        }.apply()
    }

    fun getPinLockout(): PersistedPinLockout? {
        if (!prefs.contains(KEY_PIN_LOCKOUT_VERSION)) return null

        val record = runCatching {
            if (prefs.getInt(KEY_PIN_LOCKOUT_VERSION, 0) != PIN_LOCKOUT_VERSION) return@runCatching null

            PersistedPinLockout(
                bootId = prefs.getString(KEY_PIN_LOCKOUT_BOOT_ID, null) ?: return@runCatching null,
                elapsedDeadlineMs = prefs.getLong(KEY_PIN_LOCKOUT_ELAPSED_DEADLINE, -1L),
                wallDeadlineMs = prefs.getLong(KEY_PIN_LOCKOUT_WALL_DEADLINE, -1L),
                remainingBudgetMs = prefs.getLong(KEY_PIN_LOCKOUT_REMAINING_BUDGET, -1L),
            ).takeIf {
                it.bootId.isNotBlank() &&
                    it.elapsedDeadlineMs >= 0L &&
                    it.wallDeadlineMs >= 0L &&
                    it.remainingBudgetMs > 0L
            }
        }.getOrNull()

        if (record == null) clearPinLockout()
        return record
    }

    fun setPinLockout(lockout: PersistedPinLockout) {
        prefs.edit().apply {
            putInt(KEY_PIN_LOCKOUT_VERSION, PIN_LOCKOUT_VERSION)
            putString(KEY_PIN_LOCKOUT_BOOT_ID, lockout.bootId)
            putLong(KEY_PIN_LOCKOUT_ELAPSED_DEADLINE, lockout.elapsedDeadlineMs)
            putLong(KEY_PIN_LOCKOUT_WALL_DEADLINE, lockout.wallDeadlineMs)
            putLong(KEY_PIN_LOCKOUT_REMAINING_BUDGET, lockout.remainingBudgetMs)
            // The old single elapsedRealtime deadline is removed as part of migration.
            remove(KEY_PIN_LOCKOUT_UNTIL)
        }.apply()
    }

    fun clearPinLockout() {
        prefs.edit().apply { removePinLockoutKeys() }.apply()
    }

    private fun SharedPreferences.Editor.removePinLockoutKeys() {
        remove(KEY_PIN_LOCKOUT_UNTIL)
        remove(KEY_PIN_LOCKOUT_VERSION)
        remove(KEY_PIN_LOCKOUT_BOOT_ID)
        remove(KEY_PIN_LOCKOUT_ELAPSED_DEADLINE)
        remove(KEY_PIN_LOCKOUT_WALL_DEADLINE)
        remove(KEY_PIN_LOCKOUT_REMAINING_BUDGET)
    }

    fun updateLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    fun updateThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.code).apply()
    }

    fun updateLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCATION_ENABLED, enabled).apply()
    }

    fun saveFcmToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(): String? {
        return prefs.getString(KEY_FCM_TOKEN, null)
    }

    /**
     * Records that the app has already auto-launched the POST_NOTIFICATIONS runtime dialog once,
     * so it is never triggered again automatically for this install (Android 13+).
     */
    fun setPostNotificationPermissionRequested() {
        prefs.edit().putBoolean(KEY_POST_NOTIF_PERMISSION_REQUESTED, true).apply()
    }

    /**
     * Returns whether the POST_NOTIFICATIONS runtime dialog has already been auto-launched once for
     * this install. Used to guarantee the OS dialog auto-launches at most once per install.
     */
    fun wasPostNotificationPermissionRequested(): Boolean {
        return prefs.getBoolean(KEY_POST_NOTIF_PERMISSION_REQUESTED, false)
    }

    companion object {
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_PIN_ENABLED = "pin_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_FAILED_PIN_ATTEMPTS = "failed_pin_attempts"
        // Legacy v0 value: elapsedRealtime deadline with no boot identity.
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_PIN_LOCKOUT_VERSION = "pin_lockout_version"
        private const val KEY_PIN_LOCKOUT_BOOT_ID = "pin_lockout_boot_id"
        private const val KEY_PIN_LOCKOUT_ELAPSED_DEADLINE = "pin_lockout_elapsed_deadline"
        private const val KEY_PIN_LOCKOUT_WALL_DEADLINE = "pin_lockout_wall_deadline"
        private const val KEY_PIN_LOCKOUT_REMAINING_BUDGET = "pin_lockout_remaining_budget"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_LOCATION_ENABLED = "location_enabled"
        private const val KEY_POST_NOTIF_PERMISSION_REQUESTED = "post_notif_permission_requested"
        private const val PIN_LOCKOUT_VERSION = 1
    }
}
