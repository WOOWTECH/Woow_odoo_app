package io.woowtech.odoo.ui.auth

import io.woowtech.odoo.data.repository.SettingsRepository

/**
 * One keypad digit of a PIN check, shared by the unlock gate ([AuthViewModel.enterPinDigit]) and the
 * Settings "turn App Lock off" / "change PIN" confirmations, so all use the very same verification, persisted
 * failure counter and exponential lockout ([SettingsRepository.verifyPin]).
 *
 * Returns the new accumulated PIN and the [PinEntryResult]; on `WrongPin`/`LockedOut` the PIN is
 * reset to empty so the caller re-renders the dots.
 */
internal suspend fun SettingsRepository.checkPinDigit(digit: String, currentPin: String): Pair<String, PinEntryResult> {
    val nextPin = currentPin + digit

    // PINs are a fixed 6 digits. Verify ONLY when all 6 are entered — never at intermediate
    // lengths. This removes the mid-typing stutter AND fixes a false-lockout: verifyPin
    // increments the failure counter on every wrong call, so verifying at lengths 4 and 5 used
    // to burn 2 spurious failures per correct entry (3 per wrong entry) and could trip lockout at
    // length 5 — after which the length-6 check was blocked by verifyPin's own lockout guard,
    // rejecting the CORRECT PIN. Verifying once per entry keeps the failure count correct.
    if (nextPin.length < SettingsRepository.PIN_LENGTH) {
        return nextPin to PinEntryResult.NeedMoreDigits
    }

    if (verifyPin(nextPin)) {
        return nextPin to PinEntryResult.Success
    }

    // All 6 entered and wrong — verifyPin has incremented the failure counter exactly once.
    return if (isLockedOut()) {
        "" to PinEntryResult.LockedOut
    } else {
        "" to PinEntryResult.WrongPin(remainingAttempts = getRemainingAttempts())
    }
}
