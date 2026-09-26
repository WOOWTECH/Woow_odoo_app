package io.woowtech.odoo.ui.login

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.mockk.mockk
import io.woowtech.odoo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：帳密步驟左上角的返回鍵不得畫在狀態列底下。
 *
 * 背景（2026-09-26 模擬器驗收 standard-20260926）：`MainActivity` 呼叫 `enableEdgeToEdge()`，
 * 其他畫面靠 Material3 `TopAppBar`／`Scaffold` 自動避開系統列，但 `LoginScreen` 的根 Column
 * 沒有任何 inset 處理。實機上返回 IconButton bounds 是 [22,22][148,148]，而 statusBars inset
 * 是 [0,0][1080,118]：按鈕上半部被狀態列蓋住，點圖示中心沒反應。
 *
 * 這裡對 Compose 根 View 派送一組合成的 WindowInsets（只有狀態列 118px），再量返回鍵的
 * 位置。沒有 inset 處理時按鈕頂端約在 8dp；修好後必須落在狀態列下緣之下。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class LoginScreenWindowInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val statusBarPx = 118

    @Test
    fun `Given edge-to-edge status bar inset when on credentials step then back button sits below the status bar`() {
        val viewModel = LoginViewModel(mockk(relaxed = true)).apply {
            updateServerUrl("erp.example.invalid")
            updateDatabase("demo_db")
            goToNextStep()
        }
        assertEquals(LoginStep.CREDENTIALS, viewModel.uiState.value.step)

        composeRule.setContent {
            LoginScreen(viewModel = viewModel, onLoginSuccess = {})
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
