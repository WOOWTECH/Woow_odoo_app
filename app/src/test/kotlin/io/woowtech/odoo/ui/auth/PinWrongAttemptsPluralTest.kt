package io.woowtech.odoo.ui.auth

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * verify-20260928 Android（證據 attempt2-commands.log 第 97 行）：最後一次機會顯示
 * 「Wrong PIN. 1 attempts remaining.」。`wrong_pin_attempts_remaining` 是單一 `<string>`，
 * 改成三語 `<plurals>`（英文 one／other；中文只有 other，文字與原本相同）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class PinWrongAttemptsPluralTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun enterWrongPin(remainingAttempts: Int) {
        val accountRepository = mockk<AccountRepository>(relaxed = true) { every { activeAccount } returns flowOf(null) }
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns MutableStateFlow(AppSettings(appLockEnabled = true, pinEnabled = true))
            every { getLockoutRemainingMs() } returns 0L
        }
        composeRule.setContent {
            PinScreen(
                viewModel = AuthViewModel(accountRepository, settingsRepository),
                onPinVerified = {},
                onBackClick = {},
                showBack = false,
                enterPinDigit = { digit, current ->
                    val next = current + digit
                    if (next.length < 6) next to PinEntryResult.NeedMoreDigits
                    else "" to PinEntryResult.WrongPin(remainingAttempts)
                },
            )
        }
        composeRule.waitForIdle()
        repeat(6) {
            composeRule.onNodeWithText("2").performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun `Given one attempt left when the PIN is wrong then English uses the singular`() {
        enterWrongPin(remainingAttempts = 1)

        composeRule.onNodeWithText("Wrong PIN. 1 attempt remaining.").assertExists()
    }

    @Test
    fun `Given four attempts left when the PIN is wrong then English uses the plural`() {
        enterWrongPin(remainingAttempts = 4)

        composeRule.onNodeWithText("Wrong PIN. 4 attempts remaining.").assertExists()
    }

    @Test
    @Config(qualifiers = "+zh-rTW")
    fun `Given Traditional Chinese when one attempt is left then the text is unchanged`() {
        enterWrongPin(remainingAttempts = 1)

        composeRule.onNodeWithText("PIN 碼錯誤，剩餘 1 次嘗試機會。").assertExists()
    }

    @Test
    @Config(qualifiers = "+zh-rCN")
    fun `Given Simplified Chinese when one attempt is left then the text is unchanged`() {
        enterWrongPin(remainingAttempts = 1)

        composeRule.onNodeWithText("PIN 码错误，剩余 1 次尝试机会。").assertExists()
    }
}
