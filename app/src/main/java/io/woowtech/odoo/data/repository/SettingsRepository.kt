package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import io.woowtech.odoo.ui.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

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

    fun updateBiometric(enabled: Boolean) {
        encryptedPrefs.updateBiometric(enabled)
        _settings.value = _settings.value.copy(biometricEnabled = enabled)
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
            if (System.currentTimeMillis() < lockoutUntil) {
                return false
            }
        }

        val inputHash = hashPin(pin)
        return if (inputHash == currentHash) {
            encryptedPrefs.resetFailedPinAttempts()
            _settings.value = _settings.value.copy(
                failedPinAttempts = 0,
                pinLockoutUntil = null
            )
            true
        } else {
            val attempts = encryptedPrefs.incrementFailedPinAttempts()
            if (attempts >= MAX_PIN_ATTEMPTS) {
                val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
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
        return System.currentTimeMillis() < lockoutUntil
    }

    fun getLockoutRemainingMs(): Long {
        val lockoutUntil = _settings.value.pinLockoutUntil ?: return 0
        return maxOf(0, lockoutUntil - System.currentTimeMillis())
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

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_PIN_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds
    }
}
