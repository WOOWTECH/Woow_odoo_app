package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Symmetric counterpart of [LogoutUnregisterTest].
 *
 * Verifies that AccountRepository.authenticate() fires the event-driven FCM reconcile after a
 * successful login (S2 / AC8.b — account-added event). Login now upserts the CURRENT token for
 * EACH logged-in account via [FcmTokenRepository.reconcileOnAccountAvailable] rather than
 * registering only the one new account, so a token that arrived before any account existed is
 * registered as soon as the account appears (the token-arrived-before-account race). The switch
 * path keeps its per-account register (see [SwitchAccountUnregisterTest]) because it must not undo
 * the deliberate unregister of the previously-active account.
 *
 * The bug this prevents: WoowFcmService.onNewToken fires BEFORE login (zero accounts → silent
 * no-op) → token saved to EncryptedPrefs but never POSTed to Odoo → user receives no push
 * notifications. The account-available reconcile replays the saved token once an account is present.
 * FCM is best-effort: a reconcile failure must never fail the login.
 */
class LoginRegisterTest {

    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var fcmTokenRepository: FcmTokenRepository
    private lateinit var accountRepository: AccountRepository

    // Test fixture: generic, non-production-mirroring data so this test does
    // not appear to assert anything about real usernames. The auth identity
    // values here are arbitrary mock returns — the test asserts only the FCM
    // reconcile call shape, not who logged in.
    private val authSuccess = AuthResult.Success(
        userId = 42,
        sessionId = "test-session",
        username = "testuser",
        displayName = "Test User",
    )

    @BeforeEach
    fun setup() {
        accountDao = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)
        fcmTokenRepository = mockk(relaxed = true)

        // No existing account — first-time authenticate path
        coEvery {
            accountDao.findAccount(any(), any(), any())
        } returns null
        coEvery { fcmTokenRepository.reconcileOnAccountAvailable() } returns Result.success(Unit)

        accountRepository = AccountRepository(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
        ).also {
            it.fcmTokenRepository = fcmTokenRepository
        }
    }

    @Test
    fun `Given authenticate succeeds then the account-available reconcile is fired exactly once`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns authSuccess

        accountRepository.authenticate(
            serverUrl = "https://odoo.example.com",
            database = "db",
            username = "testuser",
            password = "test-password",
        )

        // AC8.b: login triggers the event-driven reconcile (upsert current token for each account).
        coVerify(exactly = 1) { fcmTokenRepository.reconcileOnAccountAvailable() }
    }

    @Test
    fun `Given authenticate fails then the account-available reconcile is not fired`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns AuthResult.Error("bad password", AuthResult.ErrorType.INVALID_CREDENTIALS)

        accountRepository.authenticate(
            serverUrl = "https://odoo.example.com",
            database = "db",
            username = "admin",
            password = "wrong-test-password",
        )

        coVerify(exactly = 0) { fcmTokenRepository.reconcileOnAccountAvailable() }
    }

    @Test
    fun `Given the reconcile fails when authenticate succeeds then login still completes`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns authSuccess
        coEvery {
            fcmTokenRepository.reconcileOnAccountAvailable()
        } returns Result.failure(RuntimeException("Network failure"))

        val result = accountRepository.authenticate(
            serverUrl = "https://odoo.example.com",
            database = "db",
            username = "testuser",
            password = "test-password",
        )

        // Login completes — the reconcile failure is non-fatal (logged warning).
        coVerify(exactly = 1) { accountDao.insertAccount(any()) }
        coVerify(exactly = 1) { encryptedPrefs.savePassword(any(), "test-password") }
        // The auth result is still Success (FCM is best-effort).
        assert(result is AuthResult.Success)
    }

    // The switchAccount FCM register/unregister behaviour is covered in
    // detail by `SwitchAccountUnregisterTest` (4 cases including
    // previous-account unregister, idempotent self-switch, missing-active
    // account edge case, and unregister-network-failure non-fatality).
    // Avoid duplicating it here — single source of truth.
}
