package io.woowtech.odoo.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "encrypted_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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
            putInt(KEY_FAILED_PIN_ATTEMPTS, settings.failedPinAttempts)
            settings.pinLockoutUntil?.let { putLong(KEY_PIN_LOCKOUT_UNTIL, it) }
                ?: remove(KEY_PIN_LOCKOUT_UNTIL)
        }.apply()
    }

    fun getAppSettings(): AppSettings {
        return AppSettings(
            themeColor = prefs.getString(KEY_THEME_COLOR, "#6183FC") ?: "#6183FC",
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
            } else null
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
            remove(KEY_PIN_LOCKOUT_UNTIL)
        }.apply()
    }

    fun setPinLockout(until: Long) {
        prefs.edit().putLong(KEY_PIN_LOCKOUT_UNTIL, until).apply()
    }

    fun updateLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    fun updateThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.code).apply()
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
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"
    }
}
