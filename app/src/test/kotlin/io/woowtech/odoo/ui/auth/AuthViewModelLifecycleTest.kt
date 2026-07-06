package io.woowtech.odoo.ui.auth

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
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

/**
 * Tests for auth state lifecycle — process-death reset, observer idempotency,
 * auth gate round-trip. (AuthViewModelLifecycleTest — L1, L2 coverage)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelLifecycleTest {

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
                id = "acc-1",
                serverUrl = "https://example.com",
                database = "db",
                username = "admin",
                displayName = "Admin",
                userId = 1,
                isActive = true,
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
    fun `Given process death when ViewModel created then isAuthenticated starts false`() {
        // After process death in-memory state is lost — ViewModel is re-created with false
        assertFalse(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given authenticated state when onAppBackgrounded called multiple times then remains false`() = runTest {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        viewModel.requiresAuth.test {
            awaitItem() // false default
            awaitItem() // true from settings update

            viewModel.setAuthenticated(true)
            viewModel.onAppBackgrounded()
            assertFalse(viewModel.isAuthenticated.value)

            // Idempotency — calling again should not change anything
            viewModel.onAppBackgrounded()
            assertFalse(viewModel.isAuthenticated.value)

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Given app lock enabled and auth success when onAppBackgrounded then requiresAuth guard fires`() = runTest {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        viewModel.requiresAuth.test {
            awaitItem()
            awaitItem() // true

            viewModel.setAuthenticated(true)
            assertTrue(viewModel.isAuthenticated.value)
            assertTrue(viewModel.requiresAuth.value)

            viewModel.onAppBackgrounded()
            assertFalse(viewModel.isAuthenticated.value)
            // requiresAuth should still be true — only isAuthenticated was reset
            assertTrue(viewModel.requiresAuth.value)

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Given app lock disabled when onAppBackgrounded then isAuthenticated stays true`() = runTest {
        settingsFlow.value = AppSettings(appLockEnabled = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setAuthenticated(true)
        viewModel.onAppBackgrounded()

        assertTrue(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given setAuthenticated true then false when isAuthenticated emits both`() = runTest {
        viewModel.isAuthenticated.test {
            assertFalse(awaitItem()) // initial
            viewModel.setAuthenticated(true)
            assertTrue(awaitItem())
            viewModel.setAuthenticated(false)
            assertFalse(awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Given requiresAuth false when onAppBackgrounded then isAuthenticated stays unchanged`() {
        settingsFlow.value = AppSettings(appLockEnabled = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setAuthenticated(true)
        viewModel.onAppBackgrounded()

        // Lock is off — should NOT reset
        assertTrue(viewModel.isAuthenticated.value)
    }
}
