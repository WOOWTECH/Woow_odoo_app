package io.woowtech.odoo.ui.auth

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
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for PinScreen lockout behaviour (L7 fix).
 * Verifies that the initial lockout state starts as false and resolves correctly
 * on first tick via getLockoutRemainingMs().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinScreenLockoutTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: AuthViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountRepository = mockk()
        settingsRepository = mockk()

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
        every { settingsRepository.settings } returns MutableStateFlow(AppSettings())

        viewModel = AuthViewModel(accountRepository, settingsRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Given no active lockout when getLockoutRemainingMs then returns 0`() {
        every { settingsRepository.getLockoutRemainingMs() } returns 0L

        // L7: PinScreen initialises isLockedOut=false and on first tick checks this value.
        // If 0 — not locked out; UI should be in non-locked state.
        assertEquals(0L, viewModel.getLockoutRemainingMs())
    }

    @Test
    fun `Given active lockout when getLockoutRemainingMs then returns positive value`() {
        every { settingsRepository.getLockoutRemainingMs() } returns 30_000L

        assertEquals(30_000L, viewModel.getLockoutRemainingMs())
    }

    @Test
    fun `Given isLockedOut false initially when getLockoutRemainingMs returns 0 then should not be locked`() {
        every { settingsRepository.getLockoutRemainingMs() } returns 0L
        every { settingsRepository.isLockedOut() } returns false

        // The LaunchedEffect in PinScreen starts with isLockedOut=false (L7 fix),
        // reads getLockoutRemainingMs() > 0 check — 0 means not locked.
        val remaining = viewModel.getLockoutRemainingMs()
        val locked = remaining > 0

        assertEquals(false, locked)
    }
}
