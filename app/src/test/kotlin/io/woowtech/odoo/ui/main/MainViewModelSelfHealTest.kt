package io.woowtech.odoo.ui.main

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
}
