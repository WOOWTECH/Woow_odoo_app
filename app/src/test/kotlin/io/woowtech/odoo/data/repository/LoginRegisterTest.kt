package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Symmetric counterpart of [LogoutUnregisterTest].
 *
 * Verifies that AccountRepository.authenticate() and switchAccount() trigger
 * an FCM token registration POST after successful login. This is the
 * register-on-login wiring that was missing from commit 482a7bf — see
 * docs/2026-04-27-e2e-suite-results.md and CLAUDE.md
 * § "Repository-Event Symmetry".
 *
 * The bug this prevents: WoowFcmService.onNewToken fires BEFORE login
 * (zero accounts → silent no-op) → token saved to EncryptedPrefs but
 * never POSTed to Odoo → user receives no push notifications. Without
 * a register-on-login replay, the saved token sits idle forever.
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
    // register call shape, not who logged in.
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

        accountRepository = AccountRepository(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
        ).also {
            it.fcmTokenRepository = fcmTokenRepository
        }
    }

    @Test
    fun `Given saved FCM token when authenticate succeeds then registerToken is called for new account`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns authSuccess
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-abc"
        coEvery {
            fcmTokenRepository.registerToken(any(), "fcm-token-abc")
        } returns Result.success(Unit)

        accountRepository.authenticate(
            serverUrl = "https://odoo.example.com",
            database = "db",
            username = "testuser",
            password = "test-password",
        )

        coVerify(exactly = 1) {
            fcmTokenRepository.registerToken(any(), "fcm-token-abc")
        }
    }

    @Test
    fun `Given no saved FCM token when authenticate succeeds then registerToken is not called`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns authSuccess
        // The realistic case where the device hasn't received a token yet.
        coEvery { fcmTokenRepository.getStoredToken() } returns null

        accountRepository.authenticate(
            serverUrl = "https://odoo.example.com",
            database = "db",
            username = "testuser",
            password = "test-password",
        )

        coVerify(exactly = 0) {
            fcmTokenRepository.registerToken(any(), any())
        }
    }

    @Test
    fun `Given saved FCM token when authenticate fails then registerToken is not called`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns AuthResult.Error("bad password", AuthResult.ErrorType.INVALID_CREDENTIALS)
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-abc"

        accountRepository.authenticate(
            serverUrl = "https://odoo.example.com",
            database = "db",
            username = "admin",
            password = "wrong-test-password",
        )

        coVerify(exactly = 0) {
            fcmTokenRepository.registerToken(any(), any())
        }
    }

    @Test
    fun `Given register POST fails when authenticate succeeds then login still completes`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns authSuccess
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-abc"
        coEvery {
            fcmTokenRepository.registerToken(any(), "fcm-token-abc")
        } returns Result.failure(RuntimeException("Network failure"))

        val result = accountRepository.authenticate(
            serverUrl = "https://odoo.example.com",
            database = "db",
            username = "testuser",
            password = "test-password",
        )

        // Login completes — register failure is non-fatal (logged warning)
        coVerify(exactly = 1) { accountDao.insertAccount(any()) }
        coVerify(exactly = 1) { encryptedPrefs.savePassword(any(), "test-password") }
        // The auth result is still Success (FCM is best-effort)
        assert(result is AuthResult.Success)
    }

    // The switchAccount FCM register/unregister behaviour is covered in
    // detail by `SwitchAccountUnregisterTest` (4 cases including
    // previous-account unregister, idempotent self-switch, missing-active
    // account edge case, and unregister-network-failure non-fatality).
    // Avoid duplicating it here — single source of truth.
}
