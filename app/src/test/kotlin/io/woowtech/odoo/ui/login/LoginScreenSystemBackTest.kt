package io.woowtech.odoo.ui.login

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：帳密步驟按系統返回鍵（手勢或 KEYCODE_BACK）＝畫面左上角返回鍵。
 *
 * 背景（2026-09-26 模擬器驗收 standard-20260926，*-30-system-back-from-credentials）：
 * LoginScreen 沒有 CREDENTIALS 步驟的 BackHandler，系統返回直接交給 NavHost／Activity，
 * 畫面上的返回鍵（[LoginViewModel.goBack]）卻會保留網址與 DB。期望兩者一致：
 *  - 帳密步驟：系統返回 → 回伺服器步驟，網址與 DB 保留，不離開畫面
 *  - 伺服器步驟：LoginScreen 不攔截系統返回（交還給 NavHost／Activity 離開）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class LoginScreenSystemBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun credentialsStepViewModel() = LoginViewModel(mockk(relaxed = true)).apply {
        updateServerUrl("erp.example.invalid")
        updateDatabase("demo_db")
        goToNextStep()
        updateUsername("demo")
    }

    @Test
    fun `Given credentials step when system back is pressed then returns to server step keeping url and database`() {
        val viewModel = credentialsStepViewModel()
        composeRule.setContent { LoginScreen(viewModel = viewModel, onLoginSuccess = {}) }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertTrue(
                "credentials step must intercept system back",
                composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks(),
            )
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        val state = viewModel.uiState.value
        assertEquals(LoginStep.SERVER_INFO, state.step)
        assertEquals("erp.example.invalid", state.serverUrl)
        assertEquals("demo_db", state.database)
        assertFalse("system back must not finish the activity", composeRule.activity.isFinishing)
    }

    @Test
    fun `Given server step when system back is pressed then LoginScreen does not intercept it`() {
        val viewModel = LoginViewModel(mockk(relaxed = true))
        composeRule.setContent { LoginScreen(viewModel = viewModel, onLoginSuccess = {}) }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertFalse(
                "server step must leave system back to the NavHost/Activity (exit)",
                composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks(),
            )
        }
    }
}
