package io.woowtech.odoo.ui.config

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.CacheRepository
import io.woowtech.odoo.data.repository.FcmTokenRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.ui.auth.AuthViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2026-09-28 Android「移除 PIN」設定項（對齊 iOS SettingsView 的 "Remove PIN" + CurrentPinPromptView）：
 * 只有已設 PIN 時才顯示；點下去先出現「輸入目前 PIN」提示（同一個 PinScreen 鍵盤，逐位數走
 * [SettingsViewModel.enterPinToRemovePin]），不會直接移除；返回即取消、PIN 保留；正確 PIN 才移除。
 *
 * 設定頁內的 PinScreen 以 `hiltViewModel()` 取 [AuthViewModel]（只用來讀設定）；測試提供一個沒有預設 factory 的
 * ViewModelStoreOwner，其 store 先放好以假 repository 建立的實例，`hiltViewModel()` 以預設 key 取回同一個實例。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class SettingsRemovePinItemTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repository: SettingsRepository

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun showSettings(settings: AppSettings) {
        repository = mockk(relaxed = true) {
            every { this@mockk.settings } returns MutableStateFlow(settings)
            every { isLockedOut() } returns false
            every { getRemainingAttempts() } returns 4
            coEvery { verifyPin(any()) } returns false
            coEvery { verifyPin("123456") } returns true
        }
        val accountRepository = mockk<AccountRepository>(relaxed = true) {
            every { activeAccount } returns MutableStateFlow(null)
        }
        // A plain owner (no default factory) whose store already holds the AuthViewModel: hiltViewModel()
        // then builds no Hilt factory (that needs an @AndroidEntryPoint activity) and returns this instance.
        val store = ViewModelStore()
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AuthViewModel(accountRepository, repository) as T
        })[AuthViewModel::class.java]
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        val cacheRepository = mockk<CacheRepository>(relaxed = true)
        coEvery { cacheRepository.calculateCacheSize() } returns 0L
        val viewModel = SettingsViewModel(
            repository,
            cacheRepository,
            accountRepository,
            mockk<FcmTokenRepository>(relaxed = true).also { every { it.registrationStatuses } returns MutableStateFlow(emptyMap()) },
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                SettingsScreen(viewModel = viewModel, onBackClick = {})
            }
        }
        composeRule.waitForIdle()
    }

    private fun openRemovePinPrompt() {
        composeRule.onNodeWithText(string(R.string.remove_pin)).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun typePin(pin: String) {
        for (digit in pin) {
            composeRule.onNodeWithText(digit.toString()).performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun `Given no PIN when Settings is shown then there is no Remove PIN item`() {
        showSettings(AppSettings(appLockEnabled = false, pinEnabled = false))

        composeRule.onNodeWithText(string(R.string.pin_code)).assertExists()
        composeRule.onNodeWithText(string(R.string.remove_pin)).assertDoesNotExist()
    }

    @Test
    fun `Given a PIN is set when Settings is shown then the Remove PIN item is shown`() {
        showSettings(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))

        composeRule.onNodeWithText(string(R.string.remove_pin)).assertExists()
    }

    @Test
    fun `Given a PIN is set when Remove PIN is tapped then the current-PIN prompt opens and nothing is removed`() {
        showSettings(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))

        openRemovePinPrompt()

        composeRule.onNodeWithText(string(R.string.remove_pin_verify_subtitle)).assertExists()
        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText(string(R.string.remove_pin)).assertDoesNotExist()
        verify(exactly = 0) { repository.removePin() }
    }

    @Test
    fun `Given the Remove PIN prompt when Back is pressed then Settings returns and the PIN stays`() {
        showSettings(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))
        openRemovePinPrompt()

        composeRule.onNodeWithContentDescription(string(R.string.back_button)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.remove_pin_verify_subtitle)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.remove_pin)).assertExists()
        verify(exactly = 0) { repository.removePin() }
    }

    @Test
    fun `Given the Remove PIN prompt when a wrong PIN is entered then the PIN stays and the prompt remains`() {
        showSettings(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))
        openRemovePinPrompt()

        typePin("000000")

        composeRule.onNodeWithText(string(R.string.remove_pin_verify_subtitle)).assertExists()
        verify(exactly = 0) { repository.removePin() }
    }

    @Test
    fun `Given the Remove PIN prompt when the correct PIN is entered then the PIN is removed and Settings returns`() {
        showSettings(AppSettings(appLockEnabled = true, pinEnabled = true, pinHash = "salt:hash"))
        openRemovePinPrompt()

        typePin("123456")

        verify(exactly = 1) { repository.removePin() }
        composeRule.onNodeWithText(string(R.string.remove_pin_verify_subtitle)).assertDoesNotExist()
    }
}
