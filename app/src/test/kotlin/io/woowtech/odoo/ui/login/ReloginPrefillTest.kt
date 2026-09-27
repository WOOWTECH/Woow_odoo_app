package io.woowtech.odoo.ui.login

import io.mockk.coEvery
import io.mockk.mockk
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * LIVE-0927 Android r2（證據 59／60）：session 失效又沒存密碼時，使用者被丟到 Configuration 頁出不去。
 * 改為回登入頁，帶入該帳號的伺服器／資料庫／帳號，直接停在輸入密碼的步驟（對齊 iOS
 * `attemptSelfHealOrLogin` 失敗 → `.login` 且預填目前帳號）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReloginPrefillTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
        accountRepository = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Given an active account whose session expired when prefilling for re-login then server database and username are filled and the password is asked`() {
        coEvery { accountRepository.getActiveAccountOnce() } returns OdooAccount(
            id = "a1",
            serverUrl = "https://www.apporo.ai",
            database = "odoo",
            username = "demo111@example.com",
            displayName = "Demo",
            isActive = true,
        )
        val viewModel = LoginViewModel(accountRepository)

        viewModel.prefillFromActiveAccount()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LoginStep.CREDENTIALS, state.step)
        assertEquals("www.apporo.ai", state.serverUrl)
        assertEquals("odoo", state.database)
        assertEquals("demo111@example.com", state.username)
        assertEquals("", state.password)
    }

    @Test
    fun `Given no active account when prefilling then the empty server step is kept`() {
        coEvery { accountRepository.getActiveAccountOnce() } returns null
        val viewModel = LoginViewModel(accountRepository)

        viewModel.prefillFromActiveAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LoginUiState(), viewModel.uiState.value)
    }
}
