package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.api.SessionReauthenticator
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import io.woowtech.odoo.ui.login.LoginViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Android 殘留：[SessionReauthenticator.onManualReloginSucceeded] 從未被呼叫。自動重登斷路器（連續
 * [SessionReauthenticator.MAX_CONSECUTIVE_FAILURES] 次暫時性失敗或密碼被拒即開啟）在同一程序內手動重新登入
 * 成功後仍維持開啟，該帳號的 FCM／WebView self-heal 一直被拒，直到程序重啟。手動登入／切換帳號（兩品牌）
 * 以及 f66e493 的 Relogin 路由（LoginViewModel → AccountRepository.authenticate）成功後都要重設該帳號的斷路器；
 * 失敗則不得重設。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualReloginResetsReauthCircuitTest {

    private val host = "circuit.example.test"
    private val account = OdooAccount(
        id = "acc-circuit",
        serverUrl = "https://$host",
        database = "db",
        username = "user@example.test",
        displayName = "User",
        userId = 7,
        isActive = true,
    )
    private val success = AuthResult.Success(userId = 7, sessionId = "sid-fixture", username = account.username, displayName = "User")
    private val dispatcher = StandardTestDispatcher()

    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var api: OdooJsonRpcClient
    private lateinit var reauthenticator: SessionReauthenticator

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
        dao = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        api = mockk(relaxed = true)
        coEvery { dao.getAllAccountsList() } returns listOf(account)
        coEvery { dao.findAccount(any(), any(), any()) } returns account
        coEvery { dao.getAccountById(account.id) } returns account
        coEvery { dao.getActiveAccountOnce() } returns account
        every { prefs.getPassword(account.id) } returns "stored-pass"
        reauthenticator = SessionReauthenticator(dao, prefs, api, mockk(relaxed = true))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(brand: String) =
        AccountRepository(dao, prefs, api, AppBrand.forCode(brand), reauthenticator)
            .also {
                // Relaxed mocks cannot fabricate the inline Result<Unit>; stub every Result-returning call.
                it.fcmTokenRepository = mockk<FcmTokenRepository>(relaxed = true).apply {
                    every { getStoredToken() } returns "token-fixture"
                    coEvery { reconcileOnAccountAvailable() } returns Result.success(Unit)
                    coEvery { registerToken(any(), any()) } returns Result.success(Unit)
                    coEvery { unregisterToken(any()) } returns Result.success(Unit)
                }
            }

    /** Trips the breaker with transient failures; afterwards auto re-auth is declined without a network call. */
    private fun openCircuit() {
        coEvery { api.authenticateForSelfHeal(any(), any(), any(), any()) } returns
            AuthResult.Error("timeout", AuthResult.ErrorType.NETWORK_ERROR)
        repeat(SessionReauthenticator.MAX_CONSECUTIVE_FAILURES) { reauthenticator.reauthenticateForHost(host) }
        assertFalse(reauthenticator.reauthenticateForHost(host))
        coVerify(exactly = SessionReauthenticator.MAX_CONSECUTIVE_FAILURES) { api.authenticateForSelfHeal(any(), any(), any(), any()) }
    }

    @Test
    fun `Given an open circuit when a WOOW manual login succeeds then automatic re-auth is enabled again`() = runTest {
        openCircuit()
        coEvery { api.authenticate(any(), any(), any(), any()) } returns success
        coEvery { api.authenticateForSelfHeal(any(), any(), any(), any()) } returns success

        assertTrue(repository("woowtech").authenticate(account.serverUrl, account.database, account.username, "new-pass") is AuthResult.Success)

        assertTrue(reauthenticator.reauthenticateForHost(host))
    }

    @Test
    fun `Given an open circuit when a manual login fails then the circuit stays open`() = runTest {
        openCircuit()
        coEvery { api.authenticate(any(), any(), any(), any()) } returns
            AuthResult.Error("bad", AuthResult.ErrorType.INVALID_CREDENTIALS)

        repository("woowtech").authenticate(account.serverUrl, account.database, account.username, "wrong")

        coEvery { api.authenticateForSelfHeal(any(), any(), any(), any()) } returns success
        assertFalse(reauthenticator.reauthenticateForHost(host))
    }

    @Test
    fun `Given an open circuit when an Apporo manual login succeeds then automatic re-auth is enabled again`() = runTest {
        openCircuit()
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } returns success
        coEvery { api.authenticateForSelfHeal(any(), any(), any(), any()) } returns success

        assertTrue(repository("apporo").authenticate(account.serverUrl, account.database, account.username, "new-pass") is AuthResult.Success)

        assertTrue(reauthenticator.reauthenticateForHost(host))
    }

    @Test
    fun `Given an open circuit when switching to the account succeeds then automatic re-auth is enabled again`() = runTest {
        openCircuit()
        coEvery { api.authenticate(any(), any(), any(), any()) } returns success
        coEvery { api.authenticateForSelfHeal(any(), any(), any(), any()) } returns success

        assertTrue(repository("woowtech").switchAccount(account.id))

        assertTrue(reauthenticator.reauthenticateForHost(host))
    }

    @Test
    fun `Given an open circuit when the Relogin route signs in again then automatic re-auth is enabled again`() = runTest {
        openCircuit()
        coEvery { api.authenticate(any(), any(), any(), any()) } returns success
        coEvery { api.authenticateForSelfHeal(any(), any(), any(), any()) } returns success
        val login = LoginViewModel(repository("woowtech"))
        var signedIn = false

        login.prefillFromActiveAccount()
        dispatcher.scheduler.advanceUntilIdle()
        login.updatePassword("new-pass")
        login.login { signedIn = true }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(signedIn)
        assertTrue(reauthenticator.reauthenticateForHost(host))
    }
}
