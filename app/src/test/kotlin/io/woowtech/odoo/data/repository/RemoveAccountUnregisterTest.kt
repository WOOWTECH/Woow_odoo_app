package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for `AccountRepository.removeAccount` FCM unregister symmetry
 * (CLAUDE.md § "Repository-Event Symmetry").
 *
 * Without an unregister POST during account removal, Odoo's
 * `woow.fcm.device` table keeps an active record for the removed account.
 * If that account's Odoo user is later reassigned or the account.id is
 * reused, stale notifications can be delivered to this device.
 *
 * Mirrors `LogoutUnregisterTest` — same pattern, different code path.
 */
class RemoveAccountUnregisterTest {

    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var fcmTokenRepository: FcmTokenRepository
    private lateinit var accountRepository: AccountRepository

    @BeforeEach
    fun setup() {
        accountDao = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)
        fcmTokenRepository = mockk(relaxed = true)

        accountRepository = AccountRepository(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
        ).also {
            it.fcmTokenRepository = fcmTokenRepository
        }
    }

    @Test
    fun `Given account when removeAccount then unregisterToken is called before deleteAccount`() = runTest {
        coEvery { fcmTokenRepository.unregisterToken("acc-to-remove") } returns Result.success(Unit)

        accountRepository.removeAccount("acc-to-remove")

        // Order matters: unregister first (so the server still has the
        // session cookie when the POST arrives), then local cleanup.
        coVerifyOrder {
            fcmTokenRepository.unregisterToken("acc-to-remove")
            encryptedPrefs.removePassword("acc-to-remove")
            accountDao.deleteAccountById("acc-to-remove")
        }
    }

    @Test
    fun `Given unregister fails when removeAccount then deletion still proceeds`() = runTest {
        coEvery { fcmTokenRepository.unregisterToken("acc-to-remove") } returns Result.failure(
            RuntimeException("Network failure"),
        )
        var deleteWasCalled = false
        coEvery { accountDao.deleteAccountById("acc-to-remove") } answers { deleteWasCalled = true }

        accountRepository.removeAccount("acc-to-remove")

        // Network failure must NOT block the local removal — the user's
        // intent ("remove this account") is satisfied even if the device
        // is offline at the moment of removal.
        assertTrue(deleteWasCalled)
        coVerify { encryptedPrefs.removePassword("acc-to-remove") }
    }
}
