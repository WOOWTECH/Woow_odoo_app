package io.woowtech.odoo.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.FcmTokenRepository
import io.woowtech.odoo.data.repository.PushRegistrationStatus
import io.woowtech.odoo.data.repository.CacheRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppLanguage
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.woowtech.odoo.ui.auth.PinEntryResult
import io.woowtech.odoo.ui.auth.checkPinDigit
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheRepository: CacheRepository,
    accountRepository: AccountRepository,
    fcmTokenRepository: FcmTokenRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val pushRegistrationStatus: StateFlow<PushRegistrationStatus> = combine(
        accountRepository.activeAccount, fcmTokenRepository.registrationStatuses,
    ) { account, statuses ->
        account?.let { statuses[it.id] } ?: PushRegistrationStatus.NOT_CHECKED
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PushRegistrationStatus.NOT_CHECKED)

    private val _cacheSizeText = MutableStateFlow("")
    val cacheSizeText: StateFlow<String> = _cacheSizeText.asStateFlow()

    init {
        viewModelScope.launch {
            _cacheSizeText.value = formatSize(cacheRepository.calculateCacheSize())
        }
    }

    fun updateThemeColor(color: String) {
        settingsRepository.updateThemeColor(color)
    }

    fun updateReduceMotion(enabled: Boolean) {
        settingsRepository.updateReduceMotion(enabled)
    }

    /**
     * Turns App Lock on. Turning it off while a PIN is set requires the current PIN and goes through
     * [enterPinToDisableAppLock] instead (LIVE-0927 r2, iOS 32462a3 parity): a phone handed over
     * unlocked must not let anyone remove the lock. Only the legacy PIN-less state may switch off here.
     */
    fun updateAppLock(enabled: Boolean) {
        if (!enabled && settings.value.pinEnabled) {
            Timber.w("App Lock can only be turned off after verifying the current PIN")
            return
        }
        settingsRepository.updateAppLock(enabled)
    }

    /**
     * One keypad digit of the "turn App Lock off" confirmation. Uses the unlock gate's own check
     * ([verifyCurrentPinDigit]): same failure counter, same lockout — a locked-out PIN is refused
     * even when correct. App Lock is turned off only on [PinEntryResult.Success].
     */
    suspend fun enterPinToDisableAppLock(digit: String, currentPin: String): Pair<String, PinEntryResult> =
        verifyCurrentPinDigit(digit, currentPin) { settingsRepository.updateAppLock(false) }

    /**
     * One keypad digit of the "change PIN" confirmation (LIVE-0927 r3, evidence 26→28→38: Change PIN
     * used to open PIN setup directly, so anyone holding the unlocked phone could replace the PIN and
     * then pass the "turn App Lock off" check with it). Same check, counter and lockout as
     * [enterPinToDisableAppLock]; on [PinEntryResult.Success] exactly one following [setPin] is allowed.
     */
    suspend fun enterPinToChangePin(digit: String, currentPin: String): Pair<String, PinEntryResult> =
        verifyCurrentPinDigit(digit, currentPin) { pinChangeAuthorized = true }

    /** The user left PIN setup without saving: a verified-but-unused change must not linger. */
    fun cancelPinChange() {
        pinChangeAuthorized = false
    }

    /**
     * Set by a successful [enterPinToChangePin], consumed by the next [setPin] /
     * [setPinThenEnableAppLock]. Only read and written on the main thread (ViewModel calls from Compose).
     */
    private var pinChangeAuthorized = false

    /**
     * The shared "verify the current PIN" flow behind every PIN-gated Settings action: the unlock
     * gate's per-digit check ([checkPinDigit] → [SettingsRepository.verifyPin]) with its persisted
     * failure counter and exponential lockout. [onVerified] runs only on [PinEntryResult.Success].
     */
    private suspend fun verifyCurrentPinDigit(
        digit: String,
        currentPin: String,
        onVerified: () -> Unit,
    ): Pair<String, PinEntryResult> {
        val entry = settingsRepository.checkPinDigit(digit, currentPin)
        if (entry.second == PinEntryResult.Success) onVerified()
        return entry
    }

    /**
     * Replacing an existing PIN needs a prior successful [enterPinToChangePin]; first-time setup
     * (no PIN yet) does not. The authorization is single-use. Returns false when refused.
     */
    private fun consumePinWriteAuthorization(): Boolean {
        val authorized = !settings.value.pinEnabled || pinChangeAuthorized
        pinChangeAuthorized = false
        if (!authorized) Timber.w("PIN change refused: the current PIN was not verified")
        return authorized
    }

    /**
     * Updates the biometric-unlock preference.
     *
     * [canUseBiometric] must reflect the result of a fresh [BiometricManager.canAuthenticate]
     * call from the UI layer — this prevents the setting from being turned on when the device
     * has no available strong biometric hardware or no enrolled biometrics.
     */
    fun updateBiometric(enabled: Boolean, canUseBiometric: Boolean = true) {
        settingsRepository.updateBiometric(enabled = enabled, canEnable = canUseBiometric)
    }

    /**
     * Hashes [pin] with PBKDF2 (600,000 iterations) off the main thread and
     * persists the result. The computation is dispatched inside [viewModelScope]
     * so it is automatically cancelled when the ViewModel is cleared.
     *
     * Because [SettingsRepository.setPin] is now `suspend`, the return value
     * is no longer surfaced synchronously; callers in the UI layer should observe
     * the [settings] flow for the updated [pinEnabled] flag instead.
     *
     * When a PIN already exists this is refused unless [enterPinToChangePin] just verified the
     * current PIN (LIVE-0927 r3).
     */
    fun setPin(pin: String) {
        if (!consumePinWriteAuthorization()) return
        viewModelScope.launch {
            settingsRepository.setPin(pin)
        }
    }

    /**
     * Sets [pin] and, only after it is persisted, enables App Lock — in that order so the
     * `appLockEnabled ⇒ pinEnabled` invariant is never briefly violated. Used by the Settings
     * "App Lock" toggle: turning App Lock on first requires creating a PIN (the mandatory unlock
     * floor). If PIN storage fails (e.g. invalid length) App Lock is left off.
     */
    fun setPinThenEnableAppLock(pin: String) {
        if (!consumePinWriteAuthorization()) return
        viewModelScope.launch {
            val stored = settingsRepository.setPin(pin)
            if (stored) {
                settingsRepository.updateAppLock(true)
            }
        }
    }

    fun removePin() {
        settingsRepository.removePin()
    }

    fun updateLanguage(language: AppLanguage) {
        settingsRepository.updateLanguage(language)
    }

    fun updateThemeMode(mode: ThemeMode) {
        settingsRepository.updateThemeMode(mode)
    }

    /**
     * Updates whether the app shares the device location with the Odoo server
     * during attendance clock-in.
     */
    fun updateLocationEnabled(enabled: Boolean) {
        settingsRepository.updateLocationEnabled(enabled)
    }

    /**
     * Clears app cache and WebView cache via CacheRepository.
     * Does not clear login session or user settings.
     */
    fun clearCache() {
        viewModelScope.launch {
            cacheRepository.clearAppCache()
            cacheRepository.clearWebViewCache()
            _cacheSizeText.value = formatSize(cacheRepository.calculateCacheSize())
        }
    }

    private fun formatSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }
}
