package io.woowtech.odoo.ui.login

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * W1-10（EP-10R T43 修補）：登入畫面的「記住我」必須一路傳到儲存層。
 *
 * 修補前 `LoginViewModel.updateRememberMe()` 只改 UI state，`login()` 呼叫
 * `AccountRepository.authenticate()` 時沒有帶這個旗標（見 `cb4ed19`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginRememberMeWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var viewModel: LoginViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
        accountRepository = mockk(relaxed = true)
        coEvery { accountRepository.authenticate(any(), any(), any(), any(), any()) } returns
            AuthResult.Success(userId = 1, sessionId = "sid", username = "admin", displayName = "Admin")
        viewModel = LoginViewModel(accountRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loginWith(remember: Boolean?) {
        viewModel.updateServerUrl("my.odoo.example")
        viewModel.updateDatabase("prod")
        viewModel.goToNextStep()
        viewModel.updateUsername("admin")
        viewModel.updatePassword("pass")
        remember?.let(viewModel::updateRememberMe)
        viewModel.login {}
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `Given remember me unchecked when login then repository is told not to remember the password`() = runTest {
        loginWith(remember = false)

        coVerify(exactly = 1) { accountRepository.authenticate(any(), any(), any(), "pass", false) }
        coVerify(exactly = 0) { accountRepository.authenticate(any(), any(), any(), any(), true) }
    }

    @Test
    fun `Given remember me left at its checked default when login then repository remembers the password`() = runTest {
        loginWith(remember = null)

        coVerify(exactly = 1) { accountRepository.authenticate(any(), any(), any(), "pass", true) }
    }

    @Test
    fun `Given remember me unchecked then rechecked when login then latest choice wins`() = runTest {
        viewModel.updateRememberMe(false)
        loginWith(remember = true)

        coVerify(exactly = 1) { accountRepository.authenticate(any(), any(), any(), "pass", true) }
    }
}
