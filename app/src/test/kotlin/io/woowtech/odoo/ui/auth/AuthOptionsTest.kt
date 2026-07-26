package io.woowtech.odoo.ui.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Unit tests for [resolveAuthOptions] — the pure decision that guarantees the App Lock
 * screen can never render zero controls (story `story-android-applock-deadlock-fix`, WI-1).
 *
 * The decision depends only on method availability (biometricEnabled + canUseBiometric,
 * and pinEnabled), so the exhaustive matrix is the 8 combinations of those three booleans.
 * (`appLockEnabled` is intentionally not an input — see [resolveAuthOptions] docs — so the
 * "16 combinations" from the story reduce to 8 distinct inputs with the same never-empty
 * guarantee; the historical brick combination is asserted explicitly below.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthOptionsTest {

    // Exact expected AuthAction for every (biometricEnabled, canUseBiometric, pinEnabled) input.
    // biometricUsable = biometricEnabled && canUseBiometric.
    private fun cases(): List<Arguments> = listOf(
        //         bioEnabled, canUse, pin  ->  expected
        Arguments.of(false, false, false, AuthAction.RecoveryNeeded),  // the historical brick
        Arguments.of(false, false, true, AuthAction.PinOnly),
        Arguments.of(false, true, false, AuthAction.RecoveryNeeded),   // bio disabled → not usable
        Arguments.of(false, true, true, AuthAction.PinOnly),
        Arguments.of(true, false, false, AuthAction.RecoveryNeeded),   // weak/unavailable + no PIN
        Arguments.of(true, false, true, AuthAction.PinOnly),           // weak-face device unlocks by PIN
        Arguments.of(true, true, false, AuthAction.BiometricOnly),     // legacy: no PIN yet (WI-2 prevents new)
        Arguments.of(true, true, true, AuthAction.BiometricAndPin),
    )

    @ParameterizedTest(name = "bio={0} canUse={1} pin={2} -> {3}")
    @MethodSource("cases")
    fun `resolveAuthOptions returns the exact expected action for every input`(
        biometricEnabled: Boolean,
        canUseBiometric: Boolean,
        pinEnabled: Boolean,
        expected: AuthAction,
    ) {
        val result = resolveAuthOptions(
            biometricEnabled = biometricEnabled,
            canUseBiometric = canUseBiometric,
            pinEnabled = pinEnabled,
        )
        assertEquals(expected, result)
    }

    @ParameterizedTest(name = "bio={0} canUse={1} pin={2} is never absent")
    @MethodSource("cases")
    fun `resolveAuthOptions is never empty for any input (the never-brick invariant)`(
        biometricEnabled: Boolean,
        canUseBiometric: Boolean,
        pinEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") expected: AuthAction,
    ) {
        // The core regression guarantee: whatever the input, there is always an action.
        assertNotNull(
            resolveAuthOptions(
                biometricEnabled = biometricEnabled,
                canUseBiometric = canUseBiometric,
                pinEnabled = pinEnabled,
            ),
        )
    }

    @Test
    fun `Given the historical brick state when resolving then RecoveryNeeded not a blank screen`() {
        // appLockEnabled=true (screen shown) + no usable biometric + no PIN was the P0 brick:
        // the screen rendered zero controls. It must now resolve to RecoveryNeeded.
        val result = resolveAuthOptions(
            biometricEnabled = true,
            canUseBiometric = false, // Class-2/WEAK face — refused by the STRONG-only check
            pinEnabled = false,
        )
        assertEquals(AuthAction.RecoveryNeeded, result)
    }

    @Test
    fun `Given weak-face device with a PIN when resolving then PinOnly (not stranded)`() {
        val result = resolveAuthOptions(
            biometricEnabled = true,
            canUseBiometric = false,
            pinEnabled = true,
        )
        assertEquals(AuthAction.PinOnly, result)
    }
}
