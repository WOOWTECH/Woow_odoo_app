package io.woowtech.odoo.ui.navigation

import io.woowtech.odoo.ui.auth.AuthAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [authStartDestination] — the pure AuthAction→Screen routing decision (WI-2).
 *
 * Only [AuthAction.PinOnly] opens the PIN keypad directly; every other state opens the biometric
 * screen (which decides whether to also show the "Use PIN" fallback). Keeping this as a pure
 * function makes the routing testable without Compose/NavController.
 */
class AuthRoutingTest {

    @Test
    fun `Given PinOnly then routes directly to the PIN screen`() {
        assertEquals(Screen.Pin, authStartDestination(AuthAction.PinOnly))
    }

    @Test
    fun `Given BiometricOnly then routes to the biometric screen`() {
        assertEquals(Screen.Auth, authStartDestination(AuthAction.BiometricOnly))
    }

    @Test
    fun `Given BiometricAndPin then routes to the biometric screen`() {
        assertEquals(Screen.Auth, authStartDestination(AuthAction.BiometricAndPin))
    }

    @Test
    fun `Given RecoveryNeeded then routes to the biometric screen`() {
        assertEquals(Screen.Auth, authStartDestination(AuthAction.RecoveryNeeded))
    }
}
