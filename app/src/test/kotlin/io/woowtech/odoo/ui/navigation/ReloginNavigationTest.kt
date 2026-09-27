package io.woowtech.odoo.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.mockk.coEvery
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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LIVE-0927 Android r2（證據 59／60、03–05）：WebView session 失效且無法自動重新登入時，
 * `MainScreen` 直接開選單頁（Configuration）。返回 → Main 重建 → 又失效 → 又開 Configuration，
 * 系統返回與畫面返回都離不開，只能登出。
 *
 * 期望：改到「重新登入」頁（登入畫面，預填該帳號的伺服器／資料庫／帳號，停在密碼步驟），
 * 而且它是 back stack 唯一的 entry，不會再回到會自我觸發的 Main。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class ReloginNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val account = OdooAccount(
        id = "a1",
        serverUrl = "https://erp.example.invalid",
        database = "demo_db",
        username = "alice@example.invalid",
        displayName = "Alice",
        isActive = true,
    )

    private val accountRepository = mockk<AccountRepository>(relaxed = true) {
        every { activeAccount } returns MutableStateFlow(account)
        coEvery { getActiveAccountOnce() } returns account
    }

    private fun authViewModel(): AuthViewModel {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { settings } returns MutableStateFlow(AppSettings())
        }
        return AuthViewModel(accountRepository, settingsRepository)
    }

    @Test
    fun `Given the session cannot be recovered when Main asks for re-login then the prefilled sign-in is the only entry`() {
        val loginViewModel = LoginViewModel(accountRepository)
        var requestRelogin: (() -> Unit)? = null
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            WoowOdooNavHost(
                navController = navController,
                authViewModel = authViewModel(),
                loginScreen = { onSuccess -> LoginScreen(viewModel = LoginViewModel(accountRepository), onLoginSuccess = onSuccess) },
                // Main itself (WebView) is not under test; only its re-login callback.
                mainScreen = { _, onReloginRequired -> requestRelogin = onReloginRequired },
                reloginScreen = { onSuccess ->
                    LoginScreen(viewModel = loginViewModel, onLoginSuccess = onSuccess, prefillActiveAccount = true)
                },
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.Main.route, navController.currentDestination?.route)
            assertNotNull(requestRelogin)
            requestRelogin!!()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(Screen.Relogin.route, navController.currentDestination?.route)
            assertNull(
                "nothing may sit under re-login (found ${navController.previousBackStackEntry?.destination?.route})",
                navController.previousBackStackEntry,
            )
            val state = loginViewModel.uiState.value
            assertEquals(LoginStep.CREDENTIALS, state.step)
            assertEquals("erp.example.invalid", state.serverUrl)
            assertEquals("demo_db", state.database)
            assertEquals("alice@example.invalid", state.username)
            assertEquals("", state.password)
        }
    }
}
