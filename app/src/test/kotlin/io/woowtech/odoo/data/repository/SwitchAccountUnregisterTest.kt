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
 * Tests for `AccountRepository.switchAccount` FCM lifecycle.
 *
 * ## Story 8-1 (P2-9) REVERSED the behaviour this class was written to pin
 *
 * These tests used to assert that switching away from account A **unregisters A's FCM
 * token server-side**, on the rationale that otherwise "both accounts remain
 * server-side-active in `woow.fcm.device`" and A's notifications keep arriving —
 * cross-account notification bleed. That rationale was correct under the OLD server
 * schema, where one token mapped to exactly one row.
 *
 * It no longer holds, for two independent reasons:
 *
 * 1. **The server was redesigned for this exact case.** The plugin now has
 *    `UNIQUE(fcm_token, user_id)`, so one physical token legitimately maps to MANY
 *    users, and the send path DISTINCT-dedupes by token so the device still receives
 *    exactly one push. Both accounts holding a row is now the DESIGNED state, not bleed.
 * 2. **The app already contradicts itself.** `registerTokenForAllAccounts` registers
 *    EVERY account unconditionally, so the switch was unregistering a row the very next
 *    token refresh or cold-start replay puts straight back.
 *
 * And the unregister was not merely redundant — it was a weapon. A push deep link can
 * drive `switchAccount`, so before story 8-1's ambiguity fix a mis-routed notification
 * from a colliding tenant id would **kill push for an unrelated account**. Removing the
 * side effect is what takes that primitive away.
 *
 * Explicit user-initiated logout still unregisters (honest logout) — that is asserted
 * below and must not be weakened.
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
    fun `Given previous active account A when switch to B succeeds then A is NOT unregistered`() = runTest {
        // Story 8-1 AC5. Previously asserted the exact opposite (a coVerifyOrder placing
        // unregisterToken("acc-A") before the re-auth). The assertion is not weakened —
        // `exactly = 0` over ANY argument is strictly stronger than pinning one ordering.
        coEvery { accountDao.getActiveAccountOnce() } returns accountA
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-shared"
        coEvery {
            fcmTokenRepository.registerToken("acc-B", "fcm-token-shared")
        } returns Result.success(Unit)

        val ok = accountRepository.switchAccount("acc-B")

        assertTrue(ok)
        coVerify(exactly = 0) { fcmTokenRepository.unregisterToken(any()) }
        // Switching still does its actual job.
        coVerifyOrder {
            odooClient.authenticate(any(), any(), any(), any())
            accountDao.activateAccount("acc-B")
            fcmTokenRepository.registerToken("acc-B", "fcm-token-shared")
        }
    }

    @Test
    fun `Given switch to B fails re-auth then A keeps its registration and B is not registered`() = runTest {
        // The old ordering's stated trade-off — "A's FCM record is already deactivated
        // server-side before re-auth is attempted, so a failed switch costs the user A's
        // notifications until the next login" — is now simply gone. A failed switch must
        // leave A exactly as it was.
        coEvery { accountDao.getActiveAccountOnce() } returns accountA
        coEvery { fcmTokenRepository.getStoredToken() } returns "fcm-token-shared"
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns AuthResult.Error("network", AuthResult.ErrorType.NETWORK_ERROR)

        val ok = accountRepository.switchAccount("acc-B")

        assertTrue(!ok)
        coVerify(exactly = 0) { fcmTokenRepository.unregisterToken(any()) }
        coVerify(exactly = 0) { fcmTokenRepository.registerToken("acc-B", any()) }
        coVerify(exactly = 0) { accountDao.activateAccount("acc-B") }
    }

    @Test
    fun `Given an explicit logout when called then the account IS unregistered (honest logout)`() = runTest {
        // Story 8-1 AC6. Removing the unregister from the SWITCH path must not leak into
        // the LOGOUT path — logout genuinely means "stop sending me this account's pushes",
        // and it is the only remaining caller. This test replaces one that had become
        // vacuous: it stubbed unregisterToken to fail on a path that no longer calls it.
        coEvery { accountDao.getAccountById("acc-A") } returns accountA
        coEvery { fcmTokenRepository.unregisterToken("acc-A") } returns Result.success(Unit)

        accountRepository.logout("acc-A")

        coVerify(exactly = 1) { fcmTokenRepository.unregisterToken("acc-A") }
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
