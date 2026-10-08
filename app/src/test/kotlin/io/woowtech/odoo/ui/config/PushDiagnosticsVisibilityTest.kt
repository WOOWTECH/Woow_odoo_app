package io.woowtech.odoo.ui.config

import io.woowtech.odoo.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * W2-4 L8 (Pixel 7a, Play vc5 release): Settings showed the push registration diagnostics ("server has
 * no Apporo push…", "acknowledgement is not a delivery guarantee…") to end users. W1-5's recommendation:
 * release hides it, debug keeps it.
 */
class PushDiagnosticsVisibilityTest {

    @Test
    fun `Given a release build then the push diagnostics section is hidden`() {
        assertFalse(showsPushRegistrationDiagnostics(isDebugBuild = false))
    }

    @Test
    fun `Given a debug build then the push diagnostics section is shown`() {
        assertTrue(showsPushRegistrationDiagnostics(isDebugBuild = true))
    }

    @Test
    fun `Given no argument then the build type decides`() {
        assertEquals(BuildConfig.DEBUG, showsPushRegistrationDiagnostics())
    }
}
