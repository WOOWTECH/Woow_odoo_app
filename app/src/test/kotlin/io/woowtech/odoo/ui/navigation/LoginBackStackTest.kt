package io.woowtech.odoo.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.OdooAccount
import io.woowtech.odoo.ui.auth.AuthViewModel
import io.woowtech.odoo.ui.login.LoginScreen
import io.woowtech.odoo.ui.login.LoginStep
import io.woowtech.odoo.ui.login.LoginViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：沒有帳號冷啟動時，back stack 上只能有一個 Login entry。
 *
 * 背景（2026-09-26 模擬器驗收 standard-20260926，*-30/31-system-back）：帳密步驟按系統返回，
 * 出現一個網址與 DB 都清空的伺服器步驟，要再按一次才離開 App。那個空白畫面是 back stack
 * 底下的**第二個 Login entry**（各自有自己的 hiltViewModel，所以是全新的空表單）。
 *
 * 成因：`hasActiveAccount` 由 null（Splash）變 false 時，路由被做了兩次——
 *  1. `startDestination` 變成 Login → NavHost 換掉整張 graph，back stack 重設為 [Login]
 *  2. Splash 目的地自己的 LaunchedEffect 又 `navigate(Login) { popUpTo(Splash) }`；
 *     Splash 已經不在 stack 上，popUpTo 無效，於是再疊一個 → [Login, Login]
 *
 * 這裡用真的 [WoowOdooNavHost] 與真的 [AuthViewModel]（repository 以 mock 提供「沒有帳號」），
 * 只把 Login 目的地換成不經 Hilt 的 [LoginScreen]。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class LoginBackStackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Room 的 `getActiveAccount()` 不會在第一個 frame 就有值：查詢在背景跑完才發出。
     * 用 replay=1 的 SharedFlow 模擬這個時序，讓 Splash 先以 `hasActiveAccount == null`
     * 組合出來，之後才收到「沒有帳號」。
     */
    private val activeAccount = MutableSharedFlow<OdooAccount?>(replay = 1)

    private fun authViewModel(): AuthViewModel {
        val accountRepository = mockk<AccountRepository>(relaxed = true) {
            every { activeAccount } returns this@LoginBackStackTest.activeAccount
        }
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns MutableStateFlow(AppSettings())
        }
        return AuthViewModel(accountRepository, settingsRepository)
    }

    /** Cold start: compose while the account query is still pending (Splash), then deliver "no account". */
    private fun coldStartWithoutAccount(loginViewModel: LoginViewModel): () -> NavHostController {
        val authViewModel = authViewModel()
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            WoowOdooNavHost(
                navController = navController,
                authViewModel = authViewModel,
                loginScreen = { onSuccess -> LoginScreen(viewModel = loginViewModel, onLoginSuccess = onSuccess) },
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.Splash.route, navController.currentDestination?.route)
            activeAccount.tryEmit(null)
        }
        composeRule.waitForIdle()
        return { navController }
    }

    @Test
    fun `Given no account on cold start when routing settles then back stack holds exactly one Login entry`() {
        val loginViewModel = LoginViewModel(mockk(relaxed = true))
        val nav = coldStartWithoutAccount(loginViewModel)

        composeRule.runOnIdle {
            assertEquals(Screen.Login.route, nav().currentDestination?.route)
            assertNull(
                "nothing may sit under the Login entry (found ${nav().previousBackStackEntry?.destination?.route})",
                nav().previousBackStackEntry,
            )
        }
    }

    @Test
    fun `Given no account when system back on credentials then server step keeps values and next back exits`() {
        val loginViewModel = LoginViewModel(mockk(relaxed = true))
        val nav = coldStartWithoutAccount(loginViewModel)

        composeRule.runOnIdle {
            loginViewModel.updateServerUrl("erp.example.invalid")
            loginViewModel.updateDatabase("demo_db")
            loginViewModel.goToNextStep()
        }
        composeRule.waitForIdle()
        assertEquals(LoginStep.CREDENTIALS, loginViewModel.uiState.value.step)

        // 1st system back: credentials → server step on the SAME Login entry, values kept.
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.Login.route, nav().currentDestination?.route)
            assertEquals(LoginStep.SERVER_INFO, loginViewModel.uiState.value.step)
            assertEquals("erp.example.invalid", loginViewModel.uiState.value.serverUrl)
            assertEquals("demo_db", loginViewModel.uiState.value.database)
            // 2nd system back must reach the Activity (exit): no LoginScreen or NavHost
            // callback may still be enabled to swallow it.
            assertFalse(
                "server step: system back must exit, but a callback would still consume it",
                composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks(),
            )
        }
    }
}
