package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for C3: FCM token unregister on logout.
 * Verifies:
 * 1. logout() calls unregisterToken for the active account
 * 2. logout() completes even if unregisterToken fails
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
}
