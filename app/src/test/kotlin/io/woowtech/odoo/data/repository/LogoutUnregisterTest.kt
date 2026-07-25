package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for S4 (AC9) — honest logout, and its C3 predecessor (FCM unregister on logout).
 *
 * The demo444 incident: "logout" only cleared the session cookie but LEFT the local account row,
 * so a decommissioned tenant lingered forever and its dead host poisoned every reconcile. Honest
 * logout is the LEAN fix (design B): logout must (1) issue a best-effort remote `unregister_device`
 * — which the S1 server HARD-DELETEs — and (2) REMOVE the local account row (not merely clear the
 * cookie), while never blocking sign-out on a network failure. There is deliberately NO pruning
 * state machine / consecutive-REJECTED counter here (that was option-A machinery, and is GONE).
 *
 * Verifies:
 * 1. logout() calls unregisterToken for the active account (server row hard-deleted)
 * 2. logout() completes and still removes the local row even if unregisterToken fails (network)
 * 3. logging out ONE of two same-device accounts deletes ONLY its row + ONLY its server
 *    registration; the sibling keeps its row, keeps its registration (never unregistered), and is
 *    promoted to active so its push keeps flowing.
 */
class LogoutUnregisterTest {

    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var fcmTokenRepository: FcmTokenRepository
    private lateinit var accountRepository: AccountRepository

    private val testAccount = OdooAccount(
        id = "acc-test-1",
        serverUrl = "https://odoo.example.com",
        database = "db",
        username = "admin",
        displayName = "Admin",
        userId = 1,
        isActive = true,
    )

    @BeforeEach
    fun setup() {
        accountDao = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)
        fcmTokenRepository = mockk(relaxed = true)

        coEvery { accountDao.getActiveAccountOnce() } returns testAccount
        coEvery { accountDao.getAccountById("acc-test-1") } returns testAccount

        accountRepository = AccountRepository(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
        ).also {
            it.fcmTokenRepository = fcmTokenRepository
        }
    }

    @Test
    fun `Given active account when logout then unregisterToken is called before session clear`() = runTest {
        coEvery { fcmTokenRepository.unregisterToken("acc-test-1") } returns Result.success(Unit)

        accountRepository.logout()

        coVerify(exactly = 1) { fcmTokenRepository.unregisterToken("acc-test-1") }
        // Password should also be removed
        coVerify { encryptedPrefs.removePassword("acc-test-1") }
        // Ordering is load-bearing: the remote unregister must fire while the session cookie is
        // still live, i.e. BEFORE the local row + password are torn down. If we deleted first, the
        // unregister POST would have no session to authenticate with and the server row would leak.
        coVerifyOrder {
            fcmTokenRepository.unregisterToken("acc-test-1")
            encryptedPrefs.removePassword("acc-test-1")
            accountDao.deleteAccountById("acc-test-1")
        }
    }

    @Test
    fun `Given unregister fails when logout then logout completes and account is deleted`() = runTest {
        coEvery { fcmTokenRepository.unregisterToken("acc-test-1") } returns Result.failure(
            RuntimeException("Network failure")
        )
        var deleteWasCalled = false
        coEvery { accountDao.deleteAccountById("acc-test-1") } answers { deleteWasCalled = true }

        accountRepository.logout()

        // Logout should complete despite the unregister failure
        assertTrue(deleteWasCalled)
        coVerify { encryptedPrefs.removePassword("acc-test-1") }
    }

    @Test
    fun `Given two accounts on one device when logging out one then only its row and registration go and the sibling survives`() =
        runTest {
            // Two accounts on the SAME device (same server host). We log out the active one (A);
            // the sibling (B) must keep its row, keep its server registration (never unregistered),
            // and be promoted to active so its push keeps arriving. This is the multi-account
            // honest-logout guarantee: one logout deletes ONLY that tenant, not the whole device.
            val accountA = OdooAccount(
                id = "acc-A",
                serverUrl = "https://odoo.example.com",
                database = "tenant_a",
                username = "alice",
                displayName = "Alice",
                userId = 1,
                isActive = true,
            )
            val accountB = OdooAccount(
                id = "acc-B",
                serverUrl = "https://odoo.example.com",
                database = "tenant_b",
                username = "bob",
                displayName = "Bob",
                userId = 2,
                isActive = false,
            )
            coEvery { accountDao.getAccountById("acc-A") } returns accountA
            coEvery { accountDao.getAccountById("acc-B") } returns accountB
            // After A's row is deleted, only B remains locally.
            coEvery { accountDao.getAllAccountsList() } returns listOf(accountB)
            coEvery { fcmTokenRepository.unregisterToken("acc-A") } returns Result.success(Unit)

            val stayAuthenticated = accountRepository.logout("acc-A")

            // App stays authenticated because a sibling was promoted (last-logout would return false).
            assertTrue(stayAuthenticated)

            // ONLY account A's server registration is hard-deleted; B's is untouched (still gets push).
            coVerify(exactly = 1) { fcmTokenRepository.unregisterToken("acc-A") }
            coVerify(exactly = 0) { fcmTokenRepository.unregisterToken("acc-B") }

            // ONLY account A's local row + secrets are removed; B's row is never deleted.
            coVerify(exactly = 1) { accountDao.deleteAccountById("acc-A") }
            coVerify(exactly = 0) { accountDao.deleteAccountById("acc-B") }
            coVerify(exactly = 1) { encryptedPrefs.removePassword("acc-A") }
            coVerify(exactly = 0) { encryptedPrefs.removePassword("acc-B") }

            // The surviving sibling is promoted to active so the app does not strand on login.
            coVerify { accountDao.activateAccount("acc-B") }
        }

    @Test
    fun `Given the last account when logging out then its row is removed and caller is told to show login`() =
        runTest {
            // The final logout: honest logout still removes the row (no lingering demo444), and
            // signals the caller (returns false) to navigate to the login screen since none remain.
            coEvery { fcmTokenRepository.unregisterToken("acc-test-1") } returns Result.success(Unit)
            coEvery { accountDao.getAllAccountsList() } returns emptyList()

            val stayAuthenticated = accountRepository.logout("acc-test-1")

            assertFalse(stayAuthenticated)
            coVerify(exactly = 1) { fcmTokenRepository.unregisterToken("acc-test-1") }
            coVerify(exactly = 1) { accountDao.deleteAccountById("acc-test-1") }
            coVerify { encryptedPrefs.removePassword("acc-test-1") }
        }
}
