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
 * Tests for [AuthViewModel.enterPinDigit] — iOS-parity digit-by-digit PIN verification
 * with NeedMoreDigits, Success, WrongPin, and LockedOut outcomes.
 *
 * All tests use [runTest] because [AuthViewModel.enterPinDigit] and
 * [AuthViewModel.verifyPin] are now `suspend` (they delegate to
 * [SettingsRepository.verifyPin] which dispatches PBKDF2 to [Dispatchers.Default]).
 * Mocked suspend calls use [coEvery]/[coVerify].
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

    // Case 1: Single digit → NeedMoreDigits (PIN length < 4)
    @Test
    fun `Given 1 digit when enterPinDigit then returns NeedMoreDigits`() = runTest {
        coEvery { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 5

        val (pin, result) = viewModel.enterPinDigit(digit = "1", currentPin = "")

        assertEquals("1", pin)
        assertEquals(PinEntryResult.NeedMoreDigits, result)
    }

    // Case 2: 3 digits → NeedMoreDigits (still below min)
    @Test
    fun `Given 3 digits accumulated when enterPinDigit then returns NeedMoreDigits`() = runTest {
        coEvery { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns false

        val (pin, result) = viewModel.enterPinDigit(digit = "3", currentPin = "12")

        assertEquals("123", pin)
        assertEquals(PinEntryResult.NeedMoreDigits, result)
    }

    // Case 3: 4 digits and verifyPin returns true → Success
    @Test
    fun `Given correct 4-digit PIN when enterPinDigit then returns Success and sets authenticated`() = runTest {
        coEvery { settingsRepository.verifyPin("1234") } returns true

        val (pin, result) = viewModel.enterPinDigit(digit = "4", currentPin = "123")

        assertEquals("1234", pin)
        assertEquals(PinEntryResult.Success, result)
        assertTrue(viewModel.isAuthenticated.value)
    }

    // Case 4: 4 digits wrong but stored PIN could be 5 or 6 → NeedMoreDigits (no failure counted)
    @Test
    fun `Given wrong 4-digit PIN when enterPinDigit then returns NeedMoreDigits without consuming attempt`() = runTest {
        coEvery { settingsRepository.verifyPin("1234") } returns false
        every { settingsRepository.isLockedOut() } returns false

        val (pin, result) = viewModel.enterPinDigit(digit = "4", currentPin = "123")

        // Still accumulating — 4 digits but verifyPin failed; stored PIN may be longer
        assertEquals("1234", pin)
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        // verifyPin was called once to check the 4-digit match
        coVerify(exactly = 1) { settingsRepository.verifyPin("1234") }
    }

    // Case 5: 6 digits wrong → WrongPin
    @Test
    fun `Given wrong 6-digit PIN when enterPinDigit then returns WrongPin`() = runTest {
        coEvery { settingsRepository.verifyPin("123456") } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 4

        val (pin, result) = viewModel.enterPinDigit(digit = "6", currentPin = "12345")

        assertEquals("", pin)
        assertTrue(result is PinEntryResult.WrongPin)
        assertEquals(4, (result as PinEntryResult.WrongPin).remainingAttempts)
        assertFalse(viewModel.isAuthenticated.value)
    }

    // Case 6: 6 digits wrong and now locked out → LockedOut
    @Test
    fun `Given wrong 6-digit PIN causing lockout when enterPinDigit then returns LockedOut`() = runTest {
        coEvery { settingsRepository.verifyPin("000000") } returns false
        every { settingsRepository.isLockedOut() } returns true

        val (pin, result) = viewModel.enterPinDigit(digit = "0", currentPin = "00000")

        assertEquals("", pin)
        assertEquals(PinEntryResult.LockedOut, result)
    }
}
