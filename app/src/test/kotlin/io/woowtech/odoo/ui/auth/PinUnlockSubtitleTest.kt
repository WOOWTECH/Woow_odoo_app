package io.woowtech.odoo.ui.auth

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.R
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
 * LIVE-0927 Android r2（證據 45／47）：PIN 解鎖畫面的副標題顯示設定頁的說明
 * 「Set up PIN code for backup unlock」／「設定 PIN 碼作為備用解鎖方式」（PinScreen.kt:194 用了
 * `pin_code_subtitle`）。解鎖情境改用 `enter_pin_subtitle`（對齊 iOS「輸入 PIN 碼解鎖」）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class PinUnlockSubtitleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun showUnlockGate() {
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
            )
        }
        composeRule.waitForIdle()
    }

    private fun text(id: Int) = composeRule.activity.getString(id)

    @Test
    fun `Given the unlock gate then the subtitle asks to unlock and not to set up a PIN`() {
        showUnlockGate()

        composeRule.onNodeWithText("Enter your PIN to unlock").assertExists()
        composeRule.onNodeWithText(text(R.string.pin_code_subtitle)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `Given Traditional Chinese unlock gate then the subtitle reads unlock`() {
        showUnlockGate()

        composeRule.onNodeWithText("輸入 PIN 碼解鎖").assertExists()
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Given Simplified Chinese unlock gate then the subtitle reads unlock`() {
        showUnlockGate()

        composeRule.onNodeWithText("输入 PIN 码解锁").assertExists()
    }
}
