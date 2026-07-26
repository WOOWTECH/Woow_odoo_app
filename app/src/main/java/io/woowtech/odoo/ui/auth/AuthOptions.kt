package io.woowtech.odoo.ui.auth

/**
 * The unlock action the App Lock screen must offer, derived purely from persisted
 * settings plus the runtime biometric capability.
 *
 * This type is the keystone of the App Lock dead-end fix (story
 * `story-android-applock-deadlock-fix`). It is a sealed hierarchy so the UI is
 * forced to handle every case with an exhaustive `when` (no `else`), and — crucially
 * — [resolveAuthOptions] can **never** produce an "empty"/absent value. That
 * guarantee is what makes it impossible for the lock screen to render zero controls,
 * which was the P0 brick: `appLockEnabled=true` with no usable biometric and no PIN
 * previously rendered nothing and trapped the user.
 */
sealed interface AuthAction {
    /** A usable biometric is available AND a PIN fallback exists. Offer both. */
    data object BiometricAndPin : AuthAction

    /** Only PIN is available (no usable biometric). Offer PIN. */
    data object PinOnly : AuthAction

    /**
     * Only a usable biometric is available; no PIN is set. Offer biometric.
     *
     * This is a legacy/edge state: WI-2's invariant (`appLockEnabled ⇒ pinEnabled`)
     * makes it unreachable for App-Lock-on installs going forward, but the render
     * layer still handles it so older installs are not stranded.
     */
    data object BiometricOnly : AuthAction

    /**
     * No usable unlock method exists. Instead of a blank, dead-end screen the caller
     * must run the keyguard-gated recovery flow (WI-3): confirm the OS device
     * credential, then force PIN setup. Never a silent unlock.
     */
    data object RecoveryNeeded : AuthAction
}

/**
 * Resolves which unlock action the App Lock screen should present.
 *
 * A biometric counts as usable only when the user enabled it **and** the device can
 * currently perform a strong biometric auth ([canUseBiometric], which reflects
 * `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`). Weak (Class-2) biometrics
 * are intentionally not accepted, so a weak-face-only device resolves to
 * [AuthAction.PinOnly] (or [AuthAction.RecoveryNeeded] when no PIN exists) rather
 * than a dead end.
 *
 * The result is **total and never absent** — every input yields exactly one
 * [AuthAction], with [AuthAction.RecoveryNeeded] as the floor. This is the property
 * that prevents the zero-control brick.
 *
 * Note on parameters: the offered methods depend only on method availability
 * ([biometricEnabled] + [canUseBiometric], and [pinEnabled]). `appLockEnabled` is
 * deliberately **not** a parameter here — whether the lock is currently armed is a
 * navigation concern (`requiresAuth`) owned by the caller, and pairing that flag with
 * this result (e.g. "armed AND [RecoveryNeeded] ⇒ recover") is the caller's job. This
 * keeps the function free of an input it would otherwise ignore.
 */
fun resolveAuthOptions(
    biometricEnabled: Boolean,
    canUseBiometric: Boolean,
    pinEnabled: Boolean,
): AuthAction {
    val biometricUsable = biometricEnabled && canUseBiometric
    return when {
        biometricUsable && pinEnabled -> AuthAction.BiometricAndPin
        pinEnabled -> AuthAction.PinOnly
        biometricUsable -> AuthAction.BiometricOnly
        else -> AuthAction.RecoveryNeeded
    }
}
