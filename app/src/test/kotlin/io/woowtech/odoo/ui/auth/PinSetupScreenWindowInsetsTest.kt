package io.woowtech.odoo.ui.auth

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.woowtech.odoo.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：設定 PIN 畫面左上角的返回（取消）鍵不得畫在狀態列底下。
 *
 * 背景（2026-09-26，同 `LoginScreenWindowInsetsTest`）：[PinSetupScreen] 由 `SettingsScreen`
 * 全螢幕蓋在設定頁上（不在 Scaffold 內），根 Column 只有 `padding(24.dp)`；`MainActivity` 開了
 * `enableEdgeToEdge()`，返回鍵因此與狀態列重疊。
 * 修正 commit：`76aacd7`（根 Column 加 `windowInsetsPadding(WindowInsets.safeDrawing)`，做法同登入頁 `29bb12f`）。
 *
 * 對 Compose 根 View 派送只有狀態列 118px 的合成 WindowInsets，再量返回鍵位置。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class PinSetupScreenWindowInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val statusBarPx = 118

    @Test
    fun `Given edge-to-edge status bar inset when PinSetupScreen shown then back button sits below the status bar`() {
        composeRule.setContent {
            PinSetupScreen(onPinConfirmed = {}, onCancel = {})
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
