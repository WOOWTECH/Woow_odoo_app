package io.woowtech.odoo.ui.main

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.api.SessionReauthenticator
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.location.LocationPermissionGate
import io.woowtech.odoo.data.push.DeepLinkManager
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.ReloginSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for [MainViewModel.selfHealActiveAccount] — the WebView session self-heal seam that
 * delegates to the shared [SessionReauthenticator] (the same engine the FCM path uses), so the
 * WebView-expiry recovery and the API-layer recovery can never race or double-authenticate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSelfHealTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var deepLinkManager: DeepLinkManager
    private lateinit var reloginSignal: ReloginSignal
    private lateinit var locationPermissionGate: LocationPermissionGate
    private lateinit var sessionReauthenticator: SessionReauthenticator
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountRepository = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        deepLinkManager = mockk(relaxed = true)
        // MainViewModel reads reloginSignal.pending in its constructor.
        reloginSignal = mockk(relaxed = true)
        every { reloginSignal.pending } returns MutableStateFlow(null)
        locationPermissionGate = mockk(relaxed = true)
        sessionReauthenticator = mockk(relaxed = true)
        viewModel = MainViewModel(
            accountRepository = accountRepository,
            encryptedPrefs = encryptedPrefs,
            deepLinkManager = deepLinkManager,
            reloginSignal = reloginSignal,
            locationPermissionGate = locationPermissionGate,
            sessionReauthenticator = sessionReauthenticator,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Given re-auth succeeds when selfHealActiveAccount then returns true and delegates to the shared reauthenticator`() =
        runTest {
            every { sessionReauthenticator.reauthenticateForHost("odoo.example.com") } returns true

            val healed = viewModel.selfHealActiveAccount("odoo.example.com")

            assertTrue(healed)
            verify(exactly = 1) { sessionReauthenticator.reauthenticateForHost("odoo.example.com") }
        }

    @Test
    fun `Given re-auth fails when selfHealActiveAccount then returns false so the caller surfaces re-login`() =
        runTest {
            every { sessionReauthenticator.reauthenticateForHost(any()) } returns false

            val healed = viewModel.selfHealActiveAccount("odoo.example.com")

            assertFalse(healed)
            verify(exactly = 1) { sessionReauthenticator.reauthenticateForHost("odoo.example.com") }
        }


    // ──────────────────────────────────────────────────────────
    // Story 8-2 (P0-3) Hazard A — the WebView self-heal must bind the ACTIVE ACCOUNT
    // ──────────────────────────────────────────────────────────
    //
    // `reauthenticateForHost` resolves with `firstOrNull` over `lastLogin DESC`; its own KDoc calls
    // that a GUESS. With two accounts on one Odoo server, the active account's WebView expiry
    // re-authenticated the most-recently-used SIBLING — refreshing the wrong session and, once a
    // session id actually flows into the WebView, presenting B's session under A's account.
    //
    // The two tests above still pass because `accountRepository` is relaxed, so
    // `getActiveAccountOnce()` returns null and the fallback runs. They now cover the FALLBACK only.

    private fun account(id: String, host: String) = io.woowtech.odoo.domain.model.OdooAccount(
        id = id,
        serverUrl = "https://$host",
        database = "db",
        username = "user",
        displayName = "User",
    )

    @Test
    fun `Given the expiry is on the active account's own host then that ACCOUNT is re-authenticated`() =
        runTest {
            coEvery { accountRepository.getActiveAccountOnce() } returns account("acc-B", "shared.odoo.com")
            every { sessionReauthenticator.reauthenticateForAccount("acc-B") } returns true

            val healed = viewModel.selfHealActiveAccount("shared.odoo.com")

            assertTrue(healed)
            verify(exactly = 1) { sessionReauthenticator.reauthenticateForAccount("acc-B") }
            // The decisive half: the host path must NOT be taken, because it would pick the
            // most-recently-used sibling on that same host.
            verify(exactly = 0) { sessionReauthenticator.reauthenticateForHost(any()) }
        }

    @Test
    fun `Given the expiry is from a different host then it falls back rather than refreshing an unrelated account`() =
        runTest {
            coEvery { accountRepository.getActiveAccountOnce() } returns account("acc-B", "shared.odoo.com")
            every { sessionReauthenticator.reauthenticateForHost("other.odoo.com") } returns false

            viewModel.selfHealActiveAccount("other.odoo.com")

            verify(exactly = 0) { sessionReauthenticator.reauthenticateForAccount(any()) }
            verify(exactly = 1) { sessionReauthenticator.reauthenticateForHost("other.odoo.com") }
        }
}
