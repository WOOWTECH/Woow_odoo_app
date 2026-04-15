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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests covering the security-critical lifecycle properties introduced by fixes
 * L1 (observer idempotency) and L2 (process-death auth-gate guarantee).
 *
 * Each test is named with GIVEN-WHEN-THEN structure per project conventions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelLifecycleTest {

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

    // ---------- L2: process-death auth-gate guarantee ----------

    /**
     * L2 fix verification: isAuthenticated is always false when a fresh ViewModel is created.
     *
     * After process death the HiltViewModel is recreated with a new instance. The
     * authenticated flag must default to false (in-memory only, not persisted) so the
     * nav-graph imperative guard can route to Auth before the user reaches MainScreen.
     */
    @Test
    fun `Given process death simulation when AuthViewModel recreated then isAuthenticated starts false`() {
        val freshVm = newViewModel()
        assertFalse(
            "Process death must reset isAuthenticated — HiltViewModel is not persisted across process death",
            freshVm.isAuthenticated.value
        )
    }

    /**
     * L2 fix verification: requiresAuth=true combined with isAuthenticated=false produces
     * the expected "auth required" signal that the imperative LaunchedEffect in NavGraph
     * uses to force navigation to Screen.Auth.
     *
     * This test validates the state combination that the LaunchedEffect gates on:
     * `if (requiresAuth && !isAuthenticated)`.
     */
    @Test
    fun `Given requiresAuth=true and isAuthenticated=false when combined state observed then auth gate fires`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        val viewModel = newViewModel()

        // Wait for requiresAuth to settle to true.
        viewModel.requiresAuth.test {
            var last = awaitItem()
            while (!last) { last = awaitItem() }
            assertTrue("requiresAuth must be true when appLockEnabled=true", last)
            cancelAndIgnoreRemainingEvents()
        }

        // isAuthenticated must still be false (never set in this flow).
        assertFalse(
            "isAuthenticated must be false after process death / fresh ViewModel",
            viewModel.isAuthenticated.value
        )

        // The combination requiresAuth=true && !isAuthenticated is what triggers the nav guard.
        assertTrue(
            "Auth gate condition (requiresAuth && !isAuthenticated) must be true after process death",
            viewModel.requiresAuth.value && !viewModel.isAuthenticated.value
        )
    }

    // ---------- L1: onAppBackgrounded idempotency ----------

    /**
     * L1 fix verification: calling onAppBackgrounded multiple times (simulating the leaked
     * observer scenario) must be idempotent — isAuthenticated stays false after the first
     * call and repeated calls must not throw or produce unexpected side effects.
     *
     * Before the L1 fix, N leaked observers would each call onAppBackgrounded() on every
     * ON_STOP event. The ViewModel logic is idempotent (setting false when already false),
     * but this test documents and verifies that guarantee explicitly so a future refactor
     * cannot accidentally make it non-idempotent (e.g. incrementing a counter).
     */
    @Test
    fun `Given isAuthenticated=true when onAppBackgrounded called multiple times then only invalidates once and stays false`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        val viewModel = newViewModel()
        viewModel.setAuthenticated(true)

        // Let requiresAuth settle to true.
        viewModel.requiresAuth.test {
            var last = awaitItem()
            while (!last) { last = awaitItem() }
            cancelAndIgnoreRemainingEvents()
        }

        // Simulate N leaked observers all calling onAppBackgrounded on ON_STOP.
        repeat(5) { viewModel.onAppBackgrounded() }

        assertFalse(
            "isAuthenticated must be false after repeated onAppBackgrounded calls (idempotent)",
            viewModel.isAuthenticated.value
        )
    }

    @Test
    fun `Given isAuthenticated=true and requiresAuth=false when onAppBackgrounded called multiple times then isAuthenticated unchanged`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = false)
        val viewModel = newViewModel()
        viewModel.setAuthenticated(true)

        // Wait for requiresAuth to settle to false.
        viewModel.requiresAuth.test {
            val first = awaitItem()
            assertFalse("requiresAuth must be false when appLockEnabled=false", first)
            cancelAndIgnoreRemainingEvents()
        }

        // Simulating leaked observers — none of them should clear auth when lock is off.
        repeat(5) { viewModel.onAppBackgrounded() }

        assertTrue(
            "isAuthenticated must stay true when appLockEnabled=false, even with repeated callbacks",
            viewModel.isAuthenticated.value
        )
    }

    // ---------- Combined state flow: requiresAuth + isAuthenticated ----------

    /**
     * Verifies that after setAuthenticated(true) is called, the flag is reflected in the
     * StateFlow value — ensuring NavGraph's imperative LaunchedEffect can read the current
     * value synchronously without needing to collect the flow.
     */
    @Test
    fun `Given appLockEnabled=true when setAuthenticated true then isAuthenticated value is true`() {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        val viewModel = newViewModel()

        viewModel.setAuthenticated(true)

        assertTrue(
            "setAuthenticated(true) must be reflected immediately in isAuthenticated.value",
            viewModel.isAuthenticated.value
        )
    }

    /**
     * Verifies the round-trip: authenticate → background → re-check gate condition.
     * This covers the primary user-visible flow for which L2 was introduced.
     */
    @Test
    fun `Given authenticated user when app backgrounded then auth gate condition becomes true again`() = runTest(testDispatcher) {
        settingsFlow.value = AppSettings(appLockEnabled = true)
        val viewModel = newViewModel()
        viewModel.setAuthenticated(true)

        // Let requiresAuth settle.
        viewModel.requiresAuth.test {
            var last = awaitItem()
            while (!last) { last = awaitItem() }
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("isAuthenticated must be true before backgrounding", viewModel.isAuthenticated.value)

        viewModel.onAppBackgrounded()

        // After backgrounding, the auth gate condition should be true.
        assertTrue(
            "Auth gate (requiresAuth && !isAuthenticated) must be true after backgrounding",
            viewModel.requiresAuth.value && !viewModel.isAuthenticated.value
        )
    }
}
