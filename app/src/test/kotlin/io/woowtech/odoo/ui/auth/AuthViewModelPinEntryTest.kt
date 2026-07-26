package io.woowtech.odoo.ui.auth

import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [AuthViewModel.enterPinDigit] — fixed **6-digit** PIN, verified **exactly once** (at 6).
 *
 * The critical contract (fixes both the mid-typing stutter and the false-lockout): the PIN is NOT
 * verified at intermediate lengths 1–5, only at length 6. Because [SettingsRepository.verifyPin]
 * increments the persisted failure counter on every wrong call, calling it at 4/5 used to burn
 * spurious failures and could lock out a *correct* PIN. These tests assert the call count precisely
 * with [coVerify] so any regression to multi-length verification fails loudly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPinEntryTest {

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
                serverUrl = "https://odoo.example.com",
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

    // --- 1–5 digits → NeedMoreDigits, and verifyPin is NEVER called (no intermediate hash/lockout) ---

    @Test
    fun `Given 1 digit when enterPinDigit then NeedMoreDigits and no verify`() = runTest {
        coEvery { settingsRepository.verifyPin(any()) } returns false
        val (pin, result) = viewModel.enterPinDigit(digit = "1", currentPin = "")
        assertEquals("1", pin)
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        coVerify(exactly = 0) { settingsRepository.verifyPin(any()) }
    }

    @Test
    fun `Given 4 digits when enterPinDigit then NeedMoreDigits and no verify (was the false-lockout bug)`() = runTest {
        coEvery { settingsRepository.verifyPin(any()) } returns false
        val (pin, result) = viewModel.enterPinDigit(digit = "4", currentPin = "123")
        assertEquals("1234", pin)
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        // The old code verified here (and incremented the failure counter). It must not anymore.
        coVerify(exactly = 0) { settingsRepository.verifyPin(any()) }
    }

    @Test
    fun `Given 5 digits when enterPinDigit then NeedMoreDigits and no verify`() = runTest {
        coEvery { settingsRepository.verifyPin(any()) } returns false
        val (pin, result) = viewModel.enterPinDigit(digit = "5", currentPin = "1234")
        assertEquals("12345", pin)
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        coVerify(exactly = 0) { settingsRepository.verifyPin(any()) }
    }

    // --- 6 digits: the single verification point ---

    @Test
    fun `Given correct 6-digit PIN when enterPinDigit then Success and verify called exactly once`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns true

        val (pin, result) = viewModel.enterPinDigit(digit = "6", currentPin = "12345")

        assertEquals("123456", pin)
        assertEquals(PinEntryResult.Success, result)
        assertTrue(viewModel.isAuthenticated.value)
        // Exactly one verify for the whole correct entry → zero spurious failure increments.
        coVerify(exactly = 1) { settingsRepository.verifyPin("123456") }
    }

    @Test
    fun `Given wrong 6-digit PIN when enterPinDigit then WrongPin and verify called exactly once`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 4

        val (pin, result) = viewModel.enterPinDigit(digit = "6", currentPin = "12345")

        assertEquals("", pin)
        assertTrue(result is PinEntryResult.WrongPin)
        assertEquals(4, (result as PinEntryResult.WrongPin).remainingAttempts)
        assertFalse(viewModel.isAuthenticated.value)
        // A wrong entry = exactly one failed attempt (not 3).
        coVerify(exactly = 1) { settingsRepository.verifyPin("123456") }
    }

    @Test
    fun `Given wrong 6-digit PIN causing lockout when enterPinDigit then LockedOut`() = runTest {
        coEvery { settingsRepository.verifyPin("000000") } returns false
        every { settingsRepository.isLockedOut() } returns true

        val (pin, result) = viewModel.enterPinDigit(digit = "0", currentPin = "00000")

        assertEquals("", pin)
        assertEquals(PinEntryResult.LockedOut, result)
    }
}
