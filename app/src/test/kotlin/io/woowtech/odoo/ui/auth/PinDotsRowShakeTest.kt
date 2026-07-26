package io.woowtech.odoo.ui.auth

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression guard for the wrong-PIN crash.
 *
 * [PinScreen] shook the PIN-dots row with a NEGATIVE padding value (`end = (-shakeOffset * 10).dp`).
 * Compose's `Modifier.padding` requires non-negative values, so every wrong PIN threw
 * `IllegalArgumentException: Padding must be non-negative` the instant `shakeOffset` went above 0 —
 * i.e. at composition, on every wrong-PIN entry (including the reduceMotion snap() path).
 *
 * This test composes the extracted stateless [PinDotsRow] at the shaking frame (a FIXED
 * `shakeOffset = 1f`, so it does not depend on the animation clock) and asserts composition
 * completes. It FAILS on the negative-padding code and PASSES on the `Modifier.offset` fix.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
// Use a plain stub Application, not the app's WoowOdooApp — its onCreate() initializes Firebase,
// which is irrelevant to (and would crash) a pure PinDotsRow render test.
@Config(sdk = [34], application = Application::class)
class PinDotsRowShakeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Given a wrong PIN when the dots row is shaking then composition does not crash`() {
        composeRule.setContent {
            PinDotsRow(
                filledCount = 3,
                shakeOffset = 1f,
                reduceMotion = false,
                isVerifying = false,
            )
        }
        composeRule.onRoot().assertExists()
    }

    @Test
    fun `Given reduceMotion enabled when the dots row shakes then composition does not crash`() {
        composeRule.setContent {
            PinDotsRow(
                filledCount = 6,
                shakeOffset = 1f,
                reduceMotion = true,
                isVerifying = true,
            )
        }
        composeRule.onRoot().assertExists()
    }
}
