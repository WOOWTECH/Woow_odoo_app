package io.woowtech.odoo.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.BuildConfig
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * Auth lifecycle ViewModel — manages biometric / PIN state and bg→fg re-auth.
 *
 * Mirrors iOS `AuthViewModel.swift` so the two platforms stay behaviourally identical.
 * [requiresAuth] is driven by the persisted `appLockEnabled` setting; when the
 * [BuildConfig.BIOMETRIC_ENABLED] kill switch is `false` (hot-fix rollback), the gate
 * is forced off regardless of stored settings.
 *
 * ## Failure counter semantics (M3)
 *
 * There are two distinct failure counters with intentionally different scopes:
 *
 * **Biometric failure counter** — session-only, managed in [BiometricScreen] as a local
 * `failureCount` variable. It resets to zero every time the screen enters composition.
 * After [MAX_BIOMETRIC_FAILURES] failures in one session the user is routed to the PIN
 * screen. This counter is never persisted and never contributes to lockout.
 *
 * **PIN failure counter** — persisted via [SettingsRepository] / [EncryptedPrefs]. It
 * survives process death and app restarts. After 5 failures a 30-second lockout is
 * imposed; subsequent tiers escalate to 5 minutes, 30 minutes, and 1 hour at 5-failure
 * increments. See [SettingsRepository.getLockoutDuration] for the full tier table.
 *
 * The two counters are independent: a biometric session failure does not increment the
 * PIN failure counter, and a PIN failure does not reset the biometric session counter.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val hasActiveAccount: StateFlow<Boolean?> = accountRepository.activeAccount
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether the auth gate should be enforced. Emits the persisted `appLockEnabled`
     * setting unless the biometric/app-lock feature is compiled out, in which case it
     * is pinned to `false`.
     */
    val requiresAuth: StateFlow<Boolean> = if (BuildConfig.BIOMETRIC_ENABLED) {
        settingsRepository.settings
            .map { it.appLockEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    } else {
        MutableStateFlow(false).asStateFlow()
    }

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    val settings = settingsRepository.settings

    fun setAuthenticated(authenticated: Boolean) {
        _isAuthenticated.value = authenticated
    }

    /**
     * Resets the authenticated flag when the app is sent to the background, but only
     * if App Lock is enabled. Matches iOS `onAppBackgrounded()`.
     */
    fun onAppBackgrounded() {
        if (requiresAuth.value) {
            _isAuthenticated.value = false
        }
    }

    fun verifyPin(pin: String): Boolean = settingsRepository.verifyPin(pin)

    fun getRemainingAttempts(): Int = settingsRepository.getRemainingAttempts()

    fun isLockedOut(): Boolean = settingsRepository.isLockedOut()

    fun getLockoutRemainingMs(): Long = settingsRepository.getLockoutRemainingMs()

    /**
     * Appends a digit to the in-progress PIN and evaluates whether the user has
     * successfully entered the stored PIN. The stored PIN may be 4–6 digits, so a
     * mismatch shorter than 6 digits is reported as [PinEntryResult.NeedMoreDigits]
     * (the user may still be typing) rather than counted as a failed attempt.
     *
     * Only a length-6 mismatch is recorded as a failure via [SettingsRepository.verifyPin],
     * mirroring the iOS `enterPinDigit` behaviour verbatim.
     *
     * @param digit the single character to append.
     * @param currentPin the PIN accumulated so far (caller-managed UI state).
     * @return a pair of (new accumulated PIN, [PinEntryResult]). On `WrongPin` or
     * `LockedOut`, the returned PIN is empty so the caller can re-render the dots.
     */
    fun enterPinDigit(digit: String, currentPin: String): Pair<String, PinEntryResult> {
        val nextPin = currentPin + digit

        if (nextPin.length < MIN_PIN_LENGTH) {
            return nextPin to PinEntryResult.NeedMoreDigits
        }

        if (settingsRepository.verifyPin(nextPin)) {
            setAuthenticated(true)
            return nextPin to PinEntryResult.Success
        }

        // Wrong so far — but if the stored PIN is longer (5 or 6 digits) the user
        // may still be mid-entry. Only treat MAX_PIN_LENGTH mismatches as real failures
        // so we don't burn attempts on length-4 mistypes.
        if (nextPin.length < MAX_PIN_LENGTH) {
            return nextPin to PinEntryResult.NeedMoreDigits
        }

        // Length 6 and still wrong — verifyPin has already incremented the failure
        // counter inside SettingsRepository.
        return if (settingsRepository.isLockedOut()) {
            "" to PinEntryResult.LockedOut
        } else {
            "" to PinEntryResult.WrongPin(remainingAttempts = settingsRepository.getRemainingAttempts())
        }
    }

    companion object {
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 6

        /** Maximum biometric failures allowed in a single session before forcing PIN. */
        private const val MAX_BIOMETRIC_FAILURES = 3
    }
}

/**
 * Result of a single-digit PIN entry attempt. Ported 1:1 from iOS `PinEntryResult`.
 *
 * Annotated with [@Serializable] so instances can be persisted in saved-state handles
 * or passed across navigation boundaries via the type-safe navigation API.
 */
@Serializable
sealed class PinEntryResult {
    /** More digits are needed to complete the PIN. */
    @Serializable
    data object NeedMoreDigits : PinEntryResult()

    /** PIN was verified successfully. */
    @Serializable
    data object Success : PinEntryResult()

    /** PIN was incorrect. [remainingAttempts] is how many tries remain before lockout. */
    @Serializable
    data class WrongPin(val remainingAttempts: Int) : PinEntryResult()

    /** Too many failed attempts — the user is locked out. */
    @Serializable
    data object LockedOut : PinEntryResult()
}
