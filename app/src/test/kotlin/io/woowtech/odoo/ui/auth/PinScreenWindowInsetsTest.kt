package io.woowtech.odoo.ui.auth

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：解鎖用 PIN 畫面左上角的返回鍵不得畫在狀態列底下。
 *
 * 背景（2026-09-26，同 `LoginScreenWindowInsetsTest`）：`MainActivity` 呼叫 `enableEdgeToEdge()`，
 * 有 TopAppBar／Scaffold 的畫面會自動避開系統列，但 [PinScreen] 根 Column 只有 `padding(24.dp)`。
 * 返回鍵推算落在 [63,63][189,189]，與狀態列 [0,0][1080,118] 重疊，上半部點不到。
 *
 * 對 Compose 根 View 派送只有狀態列 118px 的合成 WindowInsets，再量返回鍵位置。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class PinScreenWindowInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val statusBarPx = 118

    @Test
    fun `Given edge-to-edge status bar inset when PinScreen shows back then back button sits below the status bar`() {
        val accountRepository = mockk<AccountRepository>(relaxed = true) {
            every { activeAccount } returns flowOf(null)
        }
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns MutableStateFlow(AppSettings())
            every { getLockoutRemainingMs() } returns 0L
        }
        val viewModel = AuthViewModel(accountRepository, settingsRepository)

        composeRule.setContent {
            PinScreen(viewModel = viewModel, onPinVerified = {}, onBackClick = {}, showBack = true)
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            val content = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeRule.waitForIdle()

        val backLabel = composeRule.activity.getString(R.string.back_button)
        val bounds = composeRule.onNodeWithContentDescription(backLabel)
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "back button top ${bounds.top}px must be >= status bar bottom ${statusBarPx}px",
            bounds.top >= statusBarPx,
        )
    }
}
