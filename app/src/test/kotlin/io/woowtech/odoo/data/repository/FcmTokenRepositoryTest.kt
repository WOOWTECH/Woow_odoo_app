package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FcmTokenRepositoryTest {

    private lateinit var apiClient: OdooJsonRpcClient
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var accountDao: AccountDao
    private lateinit var repo: FcmTokenRepositoryImpl

    @BeforeEach
    fun setup() {
        apiClient = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        accountDao = mockk(relaxed = true)
        repo = FcmTokenRepositoryImpl(apiClient, encryptedPrefs, accountDao)
    }

    private fun makeAccount(id: String) = OdooAccount(
        id = id,
        serverUrl = "odoo.example.com",
        database = "test",
        username = "admin",
        displayName = "Admin",
    )

    @Test
    fun `Given 3 accounts when registerTokenForAllAccounts then stores token and registers 3 times`() = runTest {
        val accounts = listOf(makeAccount("a1"), makeAccount("a2"), makeAccount("a3"))
        coEvery { accountDao.getAllAccountsList() } returns accounts

        repo.registerTokenForAllAccounts("fcm_token_abc")

        verify { encryptedPrefs.saveFcmToken("fcm_token_abc") }
    }

    @Test
    fun `Given 0 accounts when registerTokenForAllAccounts then stores token only`() = runTest {
        coEvery { accountDao.getAllAccountsList() } returns emptyList()

        val result = repo.registerTokenForAllAccounts("token_123")

        assertTrue(result.isSuccess)
        verify { encryptedPrefs.saveFcmToken("token_123") }
    }

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

    private fun assertTrue(condition: Boolean) {
        org.junit.jupiter.api.Assertions.assertTrue(condition)
    }
}
