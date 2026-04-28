package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for `AccountRepository.switchAccount` FCM lifecycle —
 * specifically the previous-account unregister that was missing
 * before this PR (CLAUDE.md § "Repository-Event Symmetry").
 *
 * Without the previous-account unregister, both accounts remain
 * server-side-active in `woow.fcm.device`. Odoo will keep delivering
 * the previous account's notifications to this device after the user
 * has switched away — cross-account notification bleed.
 */
class SwitchAccountUnregisterTest {

    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var fcmTokenRepository: FcmTokenRepository
    private lateinit var accountRepository: AccountRepository

    private val accountA = OdooAccount(
        id = "acc-A",
        serverUrl = "https://odoo.example.com",
        database = "db",
        username = "userA",
        displayName = "User A",
        userId = 11,
        isActive = true,
    )
    private val accountB = OdooAccount(
        id = "acc-B",
        serverUrl = "https://odoo.example.com",
        database = "db",
        username = "userB",
        displayName = "User B",
        userId = 22,
        isActive = false,
    )
    private val authSuccess = AuthResult.Success(
        userId = 22,
        sessionId = "test-session-B",
        username = "userB",
        displayName = "User B",
    )

    @BeforeEach
    fun setup() {
        accountDao = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)
        fcmTokenRepository = mockk(relaxed = true)

        coEvery { accountDao.getAccountById("acc-B") } returns accountB
        coEvery { encryptedPrefs.getPassword("acc-B") } returns "test-password"
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns authSuccess

        accountRepository = AccountRepository(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
        ).also {
            it.fcmTokenRepository = fcmTokenRepository
        }
    }

    @Test
    fun `Given previous active account A when switch to B succeeds then A is unregistered before re-auth and B is registered after`() = runTest {
        coEvery { accountDao.getActiveAccountOnce() } returns accountA
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-shared"
        coEvery { fcmTokenRepository.unregisterToken("acc-A") } returns Result.success(Unit)
        coEvery {
            fcmTokenRepository.registerToken("acc-B", "fcm-token-shared")
        } returns Result.success(Unit)

        val ok = accountRepository.switchAccount("acc-B")

        assertTrue(ok)
        // Critical ordering: A's unregister MUST happen BEFORE the re-auth
        // that overwrites the cookie jar. Otherwise the unregister POST
        // would carry B's session cookie and target the wrong server-side
        // record. See AccountRepository.switchAccount comment for the
        // cookie-jar-by-host rationale.
        coVerifyOrder {
            fcmTokenRepository.unregisterToken("acc-A")
            odooClient.authenticate(any(), any(), any(), any())
            accountDao.activateAccount("acc-B")
            fcmTokenRepository.registerToken("acc-B", "fcm-token-shared")
        }
    }

    @Test
    fun `Given previous account A when switch to B fails re-auth then A is still unregistered and B is not registered`() = runTest {
        // Re-auth-failure case: documents the trade-off in the new ordering.
        // A's FCM record is already deactivated server-side before re-auth
        // is attempted; if re-auth fails, the user keeps account A locally
        // but won't receive A's notifications until the next successful
        // login OR an FCM token rotation triggers re-registration.
        coEvery { accountDao.getActiveAccountOnce() } returns accountA
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-shared"
        coEvery { fcmTokenRepository.unregisterToken("acc-A") } returns Result.success(Unit)
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns AuthResult.Error("network", AuthResult.ErrorType.NETWORK_ERROR)

        val ok = accountRepository.switchAccount("acc-B")

        assertTrue(!ok)
        // A WAS unregistered (we did it before re-auth)
        coVerify(exactly = 1) { fcmTokenRepository.unregisterToken("acc-A") }
        // B was never registered (re-auth failed before we got there)
        coVerify(exactly = 0) { fcmTokenRepository.registerToken("acc-B", any()) }
        // Local state untouched: A remains the active account
        coVerify(exactly = 0) { accountDao.activateAccount("acc-B") }
    }

    @Test
    fun `Given previous-account unregister fails when switch then switch still completes`() = runTest {
        coEvery { accountDao.getActiveAccountOnce() } returns accountA
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-shared"
        coEvery { fcmTokenRepository.unregisterToken("acc-A") } returns Result.failure(
            RuntimeException("Network failure"),
        )
        coEvery {
            fcmTokenRepository.registerToken("acc-B", "fcm-token-shared")
        } returns Result.success(Unit)

        val ok = accountRepository.switchAccount("acc-B")

        // Switch must not be blocked by a stale-account-unregister
        // network failure — the user's intent is satisfied locally.
        assertTrue(ok)
        coVerify { accountDao.activateAccount("acc-B") }
        coVerify { fcmTokenRepository.registerToken("acc-B", "fcm-token-shared") }
    }

    @Test
    fun `Given no previous active account when switch then unregisterToken is not called`() = runTest {
        // Edge case: app booting from a state where no account is active
        // (shouldn't happen in normal flow, but the code path must be robust).
        coEvery { accountDao.getActiveAccountOnce() } returns null
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-shared"
        coEvery {
            fcmTokenRepository.registerToken("acc-B", "fcm-token-shared")
        } returns Result.success(Unit)

        val ok = accountRepository.switchAccount("acc-B")

        assertTrue(ok)
        coVerify(exactly = 0) { fcmTokenRepository.unregisterToken(any()) }
        coVerify(exactly = 1) { fcmTokenRepository.registerToken("acc-B", "fcm-token-shared") }
    }

    @Test
    fun `Given switch to same already-active account when called then unregisterToken is not called`() = runTest {
        // Self-switch (UI quirk where same account row is tapped). We
        // don't want to unregister the very account we're "switching" to.
        coEvery { accountDao.getActiveAccountOnce() } returns accountB.copy(isActive = true)
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-shared"
        coEvery {
            fcmTokenRepository.registerToken("acc-B", "fcm-token-shared")
        } returns Result.success(Unit)

        val ok = accountRepository.switchAccount("acc-B")

        assertTrue(ok)
        coVerify(exactly = 0) { fcmTokenRepository.unregisterToken(any()) }
    }
}
