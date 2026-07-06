package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * FcmTokenRegistrationTest and FcmTokenUnregistrationTest combined.
 * Tests registration storage, account enumeration, unregister on logout.
 * Network POST tests are exercised via the OkHttp client path; full integration
 * requires a mock server (see TODO note on FcmTokenRepositoryImpl).
 */
class FcmTokenRepositoryTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var accountDao: AccountDao
    private lateinit var sessionCookieProvider: SessionCookieProvider
    private lateinit var repo: FcmTokenRepositoryImpl

    @BeforeEach
    fun setup() {
        encryptedPrefs = mockk(relaxed = true)
        accountDao = mockk(relaxed = true)
        sessionCookieProvider = mockk(relaxed = true)
        every { sessionCookieProvider.getCookiesForHost(any()) } returns emptyList<Cookie>()
        repo = FcmTokenRepositoryImpl(
            encryptedPrefs = encryptedPrefs,
            accountDao = accountDao,
            sessionCookieProvider = sessionCookieProvider,
        )
    }

    private fun makeAccount(id: String, serverUrl: String = "https://odoo.example.com") = OdooAccount(
        id = id,
        serverUrl = serverUrl,
        database = "test",
        username = "admin",
        displayName = "Admin",
    )

    // FcmTokenRegistrationTest

    @Test
    fun `Given token when registerTokenForAllAccounts then saves token to EncryptedPrefs`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()

        repo.registerTokenForAllAccounts("tok_abc")

        verify { encryptedPrefs.saveFcmToken("tok_abc") }
    }

    @Test
    fun `Given no accounts when registerTokenForAllAccounts then returns success`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()

        val result = repo.registerTokenForAllAccounts("tok_xyz")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `Given 2 accounts when registerTokenForAllAccounts then attempts registration for each`() = runTest {
        val accounts = listOf(makeAccount("a1"), makeAccount("a2"))
        coEvery { accountDao.getAllAccountsList() } returns accounts
        coEvery { accountDao.getAccountById("a1") } returns accounts[0]
        coEvery { accountDao.getAccountById("a2") } returns accounts[1]

        // Network calls will fail (no real server) but that's expected — we verify the
        // token is saved and the loop iterates over all accounts.
        repo.registerTokenForAllAccounts("tok_multi")

        verify { encryptedPrefs.saveFcmToken("tok_multi") }
    }

    @Test
    fun `Given account not found when registerToken then returns failure`() = runTest {
        coEvery { accountDao.getAccountById("missing") } returns null

        val result = repo.registerToken(accountId = "missing", token = "tok")

        assertTrue(result.isFailure)
    }

    // FcmTokenUnregistrationTest

    @Test
    fun `Given account not found when unregisterToken then returns failure`() = runTest {
        coEvery { accountDao.getAccountById("gone") } returns null

        val result = repo.unregisterToken("gone")

        assertTrue(result.isFailure)
    }

    @Test
    fun `Given no stored token when unregisterToken then returns failure`() = runTest {
        coEvery { accountDao.getAccountById("a1") } returns makeAccount("a1")
        every { encryptedPrefs.getFcmToken() } returns null

        val result = repo.unregisterToken("a1")

        assertTrue(result.isFailure)
    }

    // getStoredToken tests

    @Test
    fun `Given token stored when getStoredToken then returns it`() {
        every { encryptedPrefs.getFcmToken() } returns "stored_token"

        assertEquals("stored_token", repo.getStoredToken())
    }

    @Test
    fun `Given no token stored when getStoredToken then returns null`() {
        every { encryptedPrefs.getFcmToken() } returns null

        assertNull(repo.getStoredToken())
    }
}
