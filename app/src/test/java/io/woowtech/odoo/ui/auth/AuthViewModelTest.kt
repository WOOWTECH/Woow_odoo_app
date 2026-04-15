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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AuthViewModel]. The ViewModel is built with a relaxed MockK of
 * [SettingsRepository] so each test can wire the verifyPin / isLockedOut / attempts
 * behaviour independently.
 *
 * GIVEN-WHEN-THEN naming is used as required by CLAUDE.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private lateinit var settingsFlow: MutableStateFlow<AppSettings>
    private lateinit var accountFlow: MutableStateFlow<OdooAccount?>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsFlow = MutableStateFlow(AppSettings())
        accountFlow = MutableStateFlow(null)
        every { settingsRepository.settings } returns settingsFlow
        every { accountRepository.activeAccount } returns accountFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): AuthViewModel = AuthViewModel(accountRepository, settingsRepository)

    // ---------- requiresAuth ----------

    @Test
    fun `Given appLockEnabled=false when settings observed then requiresAuth emits false`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = false)
        val viewModel = newViewModel()

        viewModel.requiresAuth.test {
            // Initial value from stateIn (false). Advance so map + stateIn propagates.
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Given appLockEnabled=true when settings observed then requiresAuth emits true`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        val viewModel = newViewModel()

        viewModel.requiresAuth.test {
            // First emission may be the stateIn default (false) before the upstream flows
            // through; accept either order and assert the terminal value.
            var last = awaitItem()
            while (!last) {
                last = awaitItem()
            }
            assertTrue(last)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- onAppBackgrounded ----------

    @Test
    fun `Given isAuthenticated=true when onAppBackgrounded called and appLockEnabled=true then isAuthenticated becomes false`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        val viewModel = newViewModel()
        viewModel.setAuthenticated(true)
        // Let requiresAuth settle to true.
        viewModel.requiresAuth.test {
            while (!awaitItem()) { /* wait for true */ }
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.onAppBackgrounded()

        assertFalse(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given isAuthenticated=true when onAppBackgrounded called and appLockEnabled=false then isAuthenticated stays true`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = false)
        val viewModel = newViewModel()
        viewModel.setAuthenticated(true)
        // Ensure requiresAuth collector has run so .value is correct.
        viewModel.requiresAuth.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.onAppBackgrounded()

        assertTrue(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given setAuthenticated true called then isAuthenticated emits true`() = runTest(testDispatcher) {
        val viewModel = newViewModel()

        viewModel.setAuthenticated(true)

        assertTrue(viewModel.isAuthenticated.value)
    }

    // ---------- enterPinDigit ----------

    @Test
    fun `Given PIN 1234 set when enterPinDigit appends correct digits then returns Success at length 4`() {
        every { settingsRepository.verifyPin("1") } returns false
        every { settingsRepository.verifyPin("12") } returns false
        every { settingsRepository.verifyPin("123") } returns false
        every { settingsRepository.verifyPin("1234") } returns true
        val viewModel = newViewModel()

        var pin = ""
        var result: PinEntryResult

        viewModel.enterPinDigit("1", pin).also { pin = it.first; result = it.second }
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        viewModel.enterPinDigit("2", pin).also { pin = it.first; result = it.second }
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        viewModel.enterPinDigit("3", pin).also { pin = it.first; result = it.second }
        assertEquals(PinEntryResult.NeedMoreDigits, result)
        viewModel.enterPinDigit("4", pin).also { pin = it.first; result = it.second }
        assertEquals(PinEntryResult.Success, result)
        assertTrue(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given PIN 123456 set when enterPinDigit appends 4 digits then returns NeedMoreDigits`() {
        // Stored PIN is 6 digits; verifyPin returns false for any 4- or 5-char prefix.
        every { settingsRepository.verifyPin(any()) } returns false
        val viewModel = newViewModel()

        var pin = ""
        repeat(4) { i ->
            val (next, result) = viewModel.enterPinDigit(('1' + i).toString(), pin)
            pin = next
            assertEquals("Digit ${i + 1}", PinEntryResult.NeedMoreDigits, result)
        }
        // PIN accumulator should still be 4 chars — NOT reset.
        assertEquals("1234", pin)
    }

    @Test
    fun `Given PIN 1234 set when enterPinDigit wrong digits at length 4 then returns NeedMoreDigits not WrongPin`() {
        // All prefixes wrong — mirrors iOS behaviour: at length 4/5 with a mismatch we do
        // NOT count a failed attempt, we just keep accepting digits in case the stored
        // PIN is longer than the user first assumed.
        every { settingsRepository.verifyPin(any()) } returns false
        val viewModel = newViewModel()

        var pin = ""
        repeat(4) { i ->
            val (next, result) = viewModel.enterPinDigit(('5' + i).toString(), pin)
            pin = next
            assertEquals(PinEntryResult.NeedMoreDigits, result)
        }
        assertEquals("5678", pin)
        assertFalse(viewModel.isAuthenticated.value)
        // verifyPin was called once at length 4 (before the "mismatch yet maybe longer" check)
        // but no failure-counting path (remaining attempts / lockout) should have fired.
        verify(exactly = 0) { settingsRepository.isLockedOut() }
        verify(exactly = 0) { settingsRepository.getRemainingAttempts() }
    }

    @Test
    fun `Given PIN 1234 set when enterPinDigit wrong digits at length 6 then returns WrongPin with remaining attempts`() {
        every { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 4
        val viewModel = newViewModel()

        var pin = ""
        var result: PinEntryResult = PinEntryResult.NeedMoreDigits
        repeat(6) { i ->
            val pair = viewModel.enterPinDigit(('9' - i).toString(), pin)
            pin = pair.first
            result = pair.second
        }

        assertEquals(PinEntryResult.WrongPin(remainingAttempts = 4), result)
        // On WrongPin the accumulator resets so the dots clear.
        assertEquals("", pin)
    }

    @Test
    fun `Given failedAttempts=4 and wrong PIN at length 6 then returns LockedOut`() {
        every { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns true
        every { settingsRepository.getRemainingAttempts() } returns 0
        val viewModel = newViewModel()

        var pin = ""
        var result: PinEntryResult = PinEntryResult.NeedMoreDigits
        repeat(6) {
            val pair = viewModel.enterPinDigit("0", pin)
            pin = pair.first
            result = pair.second
        }

        assertEquals(PinEntryResult.LockedOut, result)
        assertEquals("", pin)
    }

    // ---------- H4: Double-failure-count prevention ----------

    @Test
    fun `Given 6-digit stored PIN when wrong digit at length 4 then no failure recorded`() {
        // verifyPin returns false at every length (simulates 6-digit stored PIN).
        every { settingsRepository.verifyPin(any()) } returns false
        val viewModel = newViewModel()

        var pin = ""
        repeat(4) { i ->
            val (next, _) = viewModel.enterPinDigit(('1' + i).toString(), pin)
            pin = next
        }

        // At lengths 1–4 the ViewModel should NOT query isLockedOut or getRemainingAttempts
        // because no failure has been recorded yet (we're still mid-entry).
        verify(exactly = 0) { settingsRepository.isLockedOut() }
        verify(exactly = 0) { settingsRepository.getRemainingAttempts() }
    }

    @Test
    fun `Given 6-digit stored PIN when wrong digit at length 5 then no failure recorded`() {
        every { settingsRepository.verifyPin(any()) } returns false
        val viewModel = newViewModel()

        var pin = ""
        repeat(5) { i ->
            val (next, result) = viewModel.enterPinDigit(('1' + i).toString(), pin)
            pin = next
            assertEquals(
                "At length ${i + 1} result should be NeedMoreDigits",
                PinEntryResult.NeedMoreDigits,
                result
            )
        }

        // Still no failure counting at length 5
        verify(exactly = 0) { settingsRepository.isLockedOut() }
        verify(exactly = 0) { settingsRepository.getRemainingAttempts() }
        assertEquals("PIN accumulator should be 5 chars", "12345", pin)
    }

    @Test
    fun `Given 6-digit stored PIN when wrong full PIN at length 6 then exactly 1 failure path entered`() {
        every { settingsRepository.verifyPin(any()) } returns false
        every { settingsRepository.isLockedOut() } returns false
        every { settingsRepository.getRemainingAttempts() } returns 4
        val viewModel = newViewModel()

        var pin = ""
        var lastResult: PinEntryResult = PinEntryResult.NeedMoreDigits
        repeat(6) { i ->
            val pair = viewModel.enterPinDigit(('1' + i).toString(), pin)
            pin = pair.first
            lastResult = pair.second
        }

        // At exactly length 6 the failure path should be entered once
        assertEquals(PinEntryResult.WrongPin(remainingAttempts = 4), lastResult)
        verify(exactly = 1) { settingsRepository.isLockedOut() }
        verify(exactly = 1) { settingsRepository.getRemainingAttempts() }
        // PIN accumulator should be cleared on WrongPin
        assertEquals("", pin)
    }
}
