package io.woowtech.odoo.ui.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import android.util.Log
import java.util.concurrent.Executor

/**
 * Thin wrapper around [BiometricPrompt] that hides the Android framework plumbing so
 * [io.woowtech.odoo.ui.auth.BiometricScreen] can stay in pure Compose code and the whole
 * surface can be unit-tested by swapping this class for a fake.
 *
 * Enforces `BIOMETRIC_STRONG` only — we do not silently downgrade to `BIOMETRIC_WEAK`.
 * PBKDF2/PIN is the intended fallback when strong biometrics are unavailable. This
 * matches the semantics of iOS's `LAPolicy.deviceOwnerAuthenticationWithBiometrics`.
 *
 * WARNING: This helper holds a direct reference to the supplied [FragmentActivity].
 * It must not outlive that activity (e.g. never inject this class as a Hilt singleton or
 * store it in a ViewModel). Create a new instance per-screen composition and let it be
 * garbage-collected when the screen leaves the back stack.
 */
class BiometricPromptHelper(
    private val activity: FragmentActivity,
    private val executor: Executor,
    private val biometricManager: BiometricManager = BiometricManager.from(activity),
    private val promptFactory: (FragmentActivity, Executor, BiometricPrompt.AuthenticationCallback) -> BiometricPrompt =
        ::defaultPromptFactory,
) {
    /**
     * Queries whether the device can currently perform a `BIOMETRIC_STRONG` auth.
     * Maps the full set of [BiometricManager] status codes to a closed enum so callers
     * cannot forget a case.
     */
    fun canAuthenticate(): BiometricAvailability {
        val status = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.SecurityUpdateRequired
            else -> BiometricAvailability.HardwareUnavailable
        }
    }

    /**
     * Shows the system biometric prompt with a [BiometricPrompt.CryptoObject] binding.
     * Requiring a CryptoObject ensures that a successful callback cannot be spoofed:
     * the [android.security.keystore.AndroidKeyStore] key is only released to the cipher
     * after a genuine biometric match, so `onSuccess` can only fire if the hardware
     * validated the biometric AND the cipher was successfully initialised by the Keystore.
     *
     * If [cryptoObject] is null the prompt falls back to non-crypto mode (used when the
     * Keystore key has not yet been generated, e.g. first launch). Callers should prefer
     * always passing a CryptoObject; null is only safe for UI-only flows with no key
     * material involved.
     *
     * - [onSuccess]: biometric matched and, if a CryptoObject was supplied, the cipher is
     *   available on `result.cryptoObject?.cipher` for decrypting the proof token.
     * - [onFallbackToPin]: user tapped the negative button or cancelled — route to PIN.
     * - [onPermanentLockout]: biometric is locked out (temporary or permanent). Caller
     *   should route to PIN and surface re-enrollment guidance.
     * - [onError]: any other terminal error (e.g. hardware unavailable). [errString] is
     *   already localised by the platform.
     * - [onFailed]: non-terminal mismatch; the prompt stays up. Caller tracks a local
     *   counter so repeated failures can auto-route to PIN.
     */
    fun prompt(
        title: String,
        subtitle: String,
        negativeText: String,
        cryptoObject: BiometricPrompt.CryptoObject? = null,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onFallbackToPin: () -> Unit,
        onPermanentLockout: () -> Unit,
        onError: (String) -> Unit,
        onFailed: () -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // When a CryptoObject was bound, verify the cipher is present. A missing
                // cipher here would mean the platform returned success without completing
                // the Keystore operation — treat this as a failure to prevent spoofing.
                if (cryptoObject != null && result.cryptoObject?.cipher == null) {
                    Log.w(TAG, "BiometricPrompt returned success but cipher is null — treating as failure")
                    onFailed()
                    return
                }
                onSuccess(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED -> onFallbackToPin()
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> onPermanentLockout()
                    else -> onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                onFailed()
            }
        }

        val biometricPrompt = promptFactory(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        if (cryptoObject != null) {
            biometricPrompt.authenticate(info, cryptoObject)
        } else {
            biometricPrompt.authenticate(info)
        }
    }
}

/**
 * Closed enum over the [BiometricManager] `canAuthenticate` status codes. Keeps the
 * UI layer independent of framework integer constants and guarantees exhaustive `when`.
 */
enum class BiometricAvailability {
    Available,
    NoneEnrolled,
    NoHardware,
    HardwareUnavailable,
    SecurityUpdateRequired,
}

private const val TAG = "BiometricPromptHelper"

private fun defaultPromptFactory(
    activity: FragmentActivity,
    executor: Executor,
    callback: BiometricPrompt.AuthenticationCallback,
): BiometricPrompt = BiometricPrompt(activity, executor, callback)
