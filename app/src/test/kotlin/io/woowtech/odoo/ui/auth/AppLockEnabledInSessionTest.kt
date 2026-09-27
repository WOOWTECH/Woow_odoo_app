package io.woowtech.odoo.ui.auth

import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * LIVE-0927 Android r2（證據 44→45）：在設定頁開啟 App Lock、剛設定完 PIN，立刻被丟到解鎖畫面，
 * 設定頁與 back stack 都不見了，得馬上再輸入一次剛設的 PIN。
 *
 * 根因：App Lock 關著時 `isAuthenticated` 從來不會被設成 true（閘門不需要）；一開啟，
 * NavGraph 的 `requiresAuth && !isAuthenticated` 立即成立，清空整個 back stack 導到解鎖頁。
 * 使用者此刻就在 App 裡、剛證明知道 PIN，這個 session 應視為已解鎖；下次進背景才上鎖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockEnabledInSessionTest {

    private lateinit var settingsFlow: MutableStateFlow<AppSettings>
    private lateinit var viewModel: AuthViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun create(initial: AppSettings) {
        settingsFlow = MutableStateFlow(initial)
        val accountRepository = mockk<AccountRepository>(relaxed = true) { every { activeAccount } returns flowOf(null) }
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) { every { settings } returns settingsFlow }
        viewModel = AuthViewModel(accountRepository, settingsRepository)
    }

    @Test
    fun `Given App Lock off in a running session when it is turned on then the session stays unlocked`() = runTest {
        create(AppSettings(appLockEnabled = false))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.requiresAuth.collect {} }

        settingsFlow.value = AppSettings(appLockEnabled = true, pinEnabled = true)

        assertTrue(viewModel.requiresAuth.value)
        assertTrue(viewModel.isAuthenticated.value, "enabling App Lock must not lock the user out on the spot")
    }

    @Test
    fun `Given App Lock turned on in session when the app goes to the background then it locks`() = runTest {
        create(AppSettings(appLockEnabled = false))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.requiresAuth.collect {} }
        settingsFlow.value = AppSettings(appLockEnabled = true, pinEnabled = true)

        viewModel.onAppBackgrounded()

        assertFalse(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given App Lock already on at cold start then the session starts locked`() = runTest {
        create(AppSettings(appLockEnabled = true, pinEnabled = true))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.requiresAuth.collect {} }

        assertTrue(viewModel.requiresAuth.value)
        assertFalse(viewModel.isAuthenticated.value)
    }
}
