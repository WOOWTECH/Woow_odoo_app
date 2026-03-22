package io.woowtech.odoo.ui.auth

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var settingsFlow: MutableStateFlow<AppSettings>
    private lateinit var viewModel: AuthViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountRepository = mockk()
        settingsRepository = mockk()
        settingsFlow = MutableStateFlow(AppSettings())

        every { accountRepository.activeAccount } returns flowOf(
            OdooAccount(
                id = "test-account-1",
                serverUrl = "example.odoo.com",
                database = "test",
                username = "admin",
                displayName = "Admin",
                userId = 1,
                isActive = true
            )
        )
        every { settingsRepository.settings } returns settingsFlow

        viewModel = AuthViewModel(accountRepository, settingsRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Given default state when created then isAuthenticated is false`() = runTest {
        viewModel.isAuthenticated.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `Given unauthenticated when setAuthenticated true then isAuthenticated is true`() = runTest {
        viewModel.setAuthenticated(true)

        viewModel.isAuthenticated.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `Given app lock enabled and authenticated when onAppBackgrounded then isAuthenticated is false`() = runTest {
        // Enable app lock
        settingsFlow.value = AppSettings(appLockEnabled = true)

        // Subscribe to requiresAuth to activate the WhileSubscribed stateIn
        viewModel.requiresAuth.test {
            // Initial value (false) from stateIn default
            awaitItem()
            // Wait for the flow to propagate the appLockEnabled=true
            val requiresAuth = awaitItem()
            assertTrue(requiresAuth)

            // Authenticate
            viewModel.setAuthenticated(true)
            assertTrue(viewModel.isAuthenticated.value)

            // Background — should reset because appLock is enabled
            viewModel.onAppBackgrounded()
            assertFalse(viewModel.isAuthenticated.value)

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Given app lock disabled and authenticated when onAppBackgrounded then isAuthenticated stays true`() = runTest {
        // App lock disabled (default)
        settingsFlow.value = AppSettings(appLockEnabled = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setAuthenticated(true)
        assertTrue(viewModel.isAuthenticated.value)

        viewModel.onAppBackgrounded()
        // Should stay authenticated since lock is disabled
        assertTrue(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given PIN set when verifyPin correct then returns true`() {
        every { settingsRepository.verifyPin("1234") } returns true

        assertTrue(viewModel.verifyPin("1234"))
        verify { settingsRepository.verifyPin("1234") }
    }

    @Test
    fun `Given PIN set when verifyPin wrong then returns false`() {
        every { settingsRepository.verifyPin("0000") } returns false

        assertFalse(viewModel.verifyPin("0000"))
    }

    @Test
    fun `Given 5 failed attempts when getRemainingAttempts then returns 0`() {
        every { settingsRepository.getRemainingAttempts() } returns 0

        assertTrue(viewModel.getRemainingAttempts() == 0)
    }

    @Test
    fun `Given lockout active when isLockedOut then returns true`() {
        every { settingsRepository.isLockedOut() } returns true

        assertTrue(viewModel.isLockedOut())
    }
}
