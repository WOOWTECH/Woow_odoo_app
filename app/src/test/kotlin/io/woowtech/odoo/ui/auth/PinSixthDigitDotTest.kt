package io.woowtech.odoo.ui.auth

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * verify-20260928 Android（低）：輸入第 6 碼後，PBKDF2 驗證（實機約 10～18 秒）期間畫面只有 5 顆實心點，
 * 看起來像第 6 碼沒按到。根因：`PinScreen` 的按鍵處理等 `enterPinDigit` 回來才把結果寫進 `pin`，
 * 前 5 碼立即回來看不出差別，第 6 碼要等整個驗證結束。修法：先顯示該碼，再驗證。
 *
 * 點數以 PIN 點列的無障礙狀態（`n/6`，對齊 iOS accessibilityValue）觀察。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PinSixthDigitDotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val verification = CompletableDeferred<PinEntryResult>()
    private val verifiedPins = mutableListOf<String>()

    /** Digits 1–5 return at once (as checkPinDigit does); the 6th waits for [verification]. */
    private suspend fun slowSixthDigit(digit: String, current: String): Pair<String, PinEntryResult> {
        val next = current + digit
        if (next.length < 6) return next to PinEntryResult.NeedMoreDigits
        verifiedPins += next
        val result = verification.await()
        return (if (result == PinEntryResult.Success) next else "") to result
    }

    private fun show(onVerified: () -> Unit = {}) {
        val accountRepository = mockk<AccountRepository>(relaxed = true) { every { activeAccount } returns flowOf(null) }
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns MutableStateFlow(AppSettings(appLockEnabled = true, pinEnabled = true))
            every { getLockoutRemainingMs() } returns 0L
        }
        composeRule.setContent {
            PinScreen(
                viewModel = AuthViewModel(accountRepository, settingsRepository),
                onPinVerified = onVerified,
                onBackClick = {},
                showBack = false,
                enterPinDigit = ::slowSixthDigit,
            )
        }
        composeRule.waitForIdle()
    }

    private fun type(digits: String) {
        digits.forEach {
            composeRule.onNodeWithText(it.toString()).performClick()
            composeRule.waitForIdle()
        }
    }

    private fun assertDots(filled: Int) {
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$filled/6"))
            .assertExists()
    }

    @Test
    fun `Given five digits entered when the sixth is tapped then six dots show while it is verified`() {
        show()
        type("12345")
        assertDots(5)

        type("6")

        assertEquals(listOf("123456"), verifiedPins)
        assertDots(6)
    }

    @Test
    fun `Given the sixth digit is being verified when it turns out wrong then the dots clear`() {
        show()
        type("123456")
        assertDots(6)

        verification.complete(PinEntryResult.WrongPin(remainingAttempts = 4))
        composeRule.waitForIdle()

        assertDots(0)
    }

    @Test
    fun `Given the sixth digit is being verified when a digit or delete is tapped then nothing changes until it ends`() {
        var verified = false
        show(onVerified = { verified = true })
        type("123456")

        type("7")
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.waitForIdle()

        assertDots(6)
        assertEquals(listOf("123456"), verifiedPins)
        verification.complete(PinEntryResult.Success)
        composeRule.waitForIdle()
        assertEquals(true, verified)
    }
}
