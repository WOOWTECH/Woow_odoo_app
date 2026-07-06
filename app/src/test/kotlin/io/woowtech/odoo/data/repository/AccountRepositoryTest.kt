package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var repository: AccountRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountDao = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)
        repository = AccountRepository(accountDao, encryptedPrefs, odooClient)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────
    // authenticate() — Success (new account)
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class AuthenticateNewAccount {

        @Test
        fun `Given new user when authenticate succeeds then creates account and saves password`() = runTest {
            coEvery {
                odooClient.authenticate(
                    serverUrl = "https://my.odoo.com",
                    database = "prod",
                    username = "admin",
                    password = "secret"
                )
            } returns AuthResult.Success(
                userId = 42,
                sessionId = "sess-123",
                username = "admin",
                displayName = "Admin User"
            )
            coEvery { accountDao.findAccount(any(), any(), any()) } returns null

            val result = repository.authenticate(
                serverUrl = "https://my.odoo.com",
                database = "prod",
                username = "admin",
                password = "secret"
            )

            assertTrue(result is AuthResult.Success)
            coVerify { accountDao.deactivateAllAccounts() }
            coVerify {
                accountDao.insertAccount(match { account ->
                    account.serverUrl == "https://my.odoo.com" &&
                    account.database == "prod" &&
                    account.username == "admin" &&
                    account.displayName == "Admin User" &&
                    account.userId == 42 &&
                    account.isActive
                })
            }
            verify { encryptedPrefs.savePassword(any(), eq("secret")) }
        }

        @Test
        fun `Given bare domain when authenticate then prepends https before calling client`() = runTest {
            coEvery {
                odooClient.authenticate(any(), any(), any(), any())
            } returns AuthResult.Success(
                userId = 1,
                sessionId = "s",
                username = "admin",
                displayName = "Admin"
            )
            coEvery { accountDao.findAccount(any(), any(), any()) } returns null

            repository.authenticate(
                serverUrl = "my.odoo.com",
                database = "db",
                username = "admin",
                password = "pass"
            )

            coVerify {
                odooClient.authenticate(
                    serverUrl = "https://my.odoo.com",
                    database = "db",
                    username = "admin",
                    password = "pass"
                )
            }
        }

        @Test
        fun `Given URL already has https when authenticate then does not double-prefix`() = runTest {
            coEvery {
                odooClient.authenticate(any(), any(), any(), any())
            } returns AuthResult.Success(
                userId = 1,
                sessionId = "s",
                username = "admin",
                displayName = "Admin"
            )
            coEvery { accountDao.findAccount(any(), any(), any()) } returns null

            repository.authenticate(
                serverUrl = "https://my.odoo.com",
                database = "db",
                username = "admin",
                password = "pass"
            )

            coVerify {
                odooClient.authenticate(
                    serverUrl = "https://my.odoo.com",
                    database = "db",
                    username = "admin",
                    password = "pass"
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // authenticate() — Success (existing account)
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class AuthenticateExistingAccount {

        @Test
        fun `Given existing account when authenticate succeeds then updates existing instead of creating new`() = runTest {
            val existingAccount = OdooAccount(
                id = "existing-id-123",
                serverUrl = "https://my.odoo.com",
                database = "prod",
                username = "admin",
                displayName = "Old Name",
                userId = 10,
                isActive = false
            )
            coEvery { accountDao.findAccount("https://my.odoo.com", "prod", "admin") } returns existingAccount
            coEvery {
                odooClient.authenticate(any(), any(), any(), any())
            } returns AuthResult.Success(
                userId = 42,
                sessionId = "new-session",
                username = "admin",
                displayName = "New Name"
            )

            repository.authenticate(
                serverUrl = "https://my.odoo.com",
                database = "prod",
                username = "admin",
                password = "newpass"
            )

            coVerify {
                accountDao.insertAccount(match { account ->
                    account.id == "existing-id-123" &&
                    account.displayName == "New Name" &&
                    account.userId == 42 &&
                    account.isActive
                })
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // authenticate() — Deactivation Order
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given successful auth when authenticate then deactivates all accounts before inserting new active`() = runTest {
        coEvery {
            odooClient.authenticate(any(), any(), any(), any())
        } returns AuthResult.Success(
            userId = 1,
            sessionId = "s",
            username = "u",
            displayName = "d"
        )
        coEvery { accountDao.findAccount(any(), any(), any()) } returns null

        repository.authenticate(
            serverUrl = "https://my.odoo.com",
            database = "db",
            username = "u",
            password = "p"
        )

        coVerifyOrder {
            accountDao.deactivateAllAccounts()
            accountDao.insertAccount(any())
        }
    }

    // ──────────────────────────────────────────────────────────
    // authenticate() — Failure
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class AuthenticateFailure {

        @Test
        fun `Given auth fails when authenticate then returns error without saving account`() = runTest {
            coEvery {
                odooClient.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("Invalid credentials", AuthResult.ErrorType.INVALID_CREDENTIALS)

            val result = repository.authenticate(
                serverUrl = "https://my.odoo.com",
                database = "db",
                username = "admin",
                password = "wrong"
            )

            assertTrue(result is AuthResult.Error)
            assertEquals(AuthResult.ErrorType.INVALID_CREDENTIALS, (result as AuthResult.Error).type)
            coVerify(exactly = 0) { accountDao.deactivateAllAccounts() }
            coVerify(exactly = 0) { accountDao.insertAccount(any()) }
            verify(exactly = 0) { encryptedPrefs.savePassword(any(), any()) }
        }

        @Test
        fun `Given network error when authenticate then returns error from client`() = runTest {
            coEvery {
                odooClient.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("Network unreachable", AuthResult.ErrorType.NETWORK_ERROR)

            val result = repository.authenticate(
                serverUrl = "https://unreachable.com",
                database = "db",
                username = "admin",
                password = "pass"
            )

            assertTrue(result is AuthResult.Error)
            assertEquals(AuthResult.ErrorType.NETWORK_ERROR, (result as AuthResult.Error).type)
        }
    }

    // ──────────────────────────────────────────────────────────
    // switchAccount()
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class SwitchAccount {

        @Test
        fun `Given valid account with password when switchAccount and auth succeeds then activates account`() = runTest {
            val account = OdooAccount(
                id = "acc-1",
                serverUrl = "https://my.odoo.com",
                database = "prod",
                username = "admin",
                displayName = "Admin",
                userId = 1,
                isActive = false
            )
            coEvery { accountDao.getAccountById("acc-1") } returns account
            every { encryptedPrefs.getPassword("acc-1") } returns "storedPass"
            coEvery {
                odooClient.authenticate(
                    serverUrl = "https://my.odoo.com",
                    database = "prod",
                    username = "admin",
                    password = "storedPass"
                )
            } returns AuthResult.Success(
                userId = 1,
                sessionId = "new-session",
                username = "admin",
                displayName = "Admin"
            )

            val result = repository.switchAccount("acc-1")

            assertTrue(result)
            coVerify { accountDao.deactivateAllAccounts() }
            coVerify { accountDao.activateAccount("acc-1") }
            coVerify { accountDao.updateLastLogin(any(), any()) }
        }

        @Test
        fun `Given account not found when switchAccount then returns false`() = runTest {
            coEvery { accountDao.getAccountById("nonexistent") } returns null

            val result = repository.switchAccount("nonexistent")

            assertFalse(result)
        }

        @Test
        fun `Given no stored password when switchAccount then returns false`() = runTest {
            val account = OdooAccount(
                id = "acc-1",
                serverUrl = "https://my.odoo.com",
                database = "db",
                username = "admin",
                displayName = "Admin"
            )
            coEvery { accountDao.getAccountById("acc-1") } returns account
            every { encryptedPrefs.getPassword("acc-1") } returns null

            val result = repository.switchAccount("acc-1")

            assertFalse(result)
        }

        @Test
        fun `Given re-authentication fails when switchAccount then returns false and does not activate`() = runTest {
            val account = OdooAccount(
                id = "acc-1",
                serverUrl = "https://my.odoo.com",
                database = "db",
                username = "admin",
                displayName = "Admin"
            )
            coEvery { accountDao.getAccountById("acc-1") } returns account
            every { encryptedPrefs.getPassword("acc-1") } returns "pass"
            coEvery {
                odooClient.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("expired", AuthResult.ErrorType.SESSION_EXPIRED)

            val result = repository.switchAccount("acc-1")

            assertFalse(result)
            coVerify(exactly = 0) { accountDao.deactivateAllAccounts() }
            coVerify(exactly = 0) { accountDao.activateAccount(any()) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // logout()
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class Logout {

        @Test
        fun `Given specific account ID when logout then clears cookies password and deletes account`() = runTest {
            val account = OdooAccount(
                id = "acc-1",
                serverUrl = "https://my.odoo.com",
                database = "db",
                username = "admin",
                displayName = "Admin"
            )
            coEvery { accountDao.getAccountById("acc-1") } returns account

            repository.logout("acc-1")

            verify { odooClient.clearCookies("my.odoo.com") }
            verify { encryptedPrefs.removePassword("acc-1") }
            coVerify { accountDao.deleteAccountById("acc-1") }
        }

        @Test
        fun `Given no account ID when logout then uses active account`() = runTest {
            val activeAccount = OdooAccount(
                id = "active-acc",
                serverUrl = "https://active.odoo.com",
                database = "db",
                username = "admin",
                displayName = "Admin",
                isActive = true
            )
            coEvery { accountDao.getActiveAccountOnce() } returns activeAccount
            coEvery { accountDao.getAccountById("active-acc") } returns activeAccount

            repository.logout()

            verify { odooClient.clearCookies("active.odoo.com") }
            verify { encryptedPrefs.removePassword("active-acc") }
            coVerify { accountDao.deleteAccountById("active-acc") }
        }

        @Test
        fun `Given no active account and no ID when logout then does nothing`() = runTest {
            coEvery { accountDao.getActiveAccountOnce() } returns null

            repository.logout()

            verify(exactly = 0) { odooClient.clearCookies(any()) }
            verify(exactly = 0) { encryptedPrefs.removePassword(any()) }
            coVerify(exactly = 0) { accountDao.deleteAccountById(any()) }
        }

        @Test
        fun `Given account ID but account not in database when logout then does nothing after ID lookup`() = runTest {
            coEvery { accountDao.getAccountById("gone-id") } returns null

            repository.logout("gone-id")

            verify(exactly = 0) { odooClient.clearCookies(any()) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // removeAccount()
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class RemoveAccount {

        @Test
        fun `Given account exists when removeAccount then removes password and deletes from dao`() = runTest {
            repository.removeAccount("acc-1")

            verify { encryptedPrefs.removePassword("acc-1") }
            coVerify { accountDao.deleteAccountById("acc-1") }
        }

        @Test
        fun `Given removeAccount when called then does NOT clear cookies unlike logout`() = runTest {
            repository.removeAccount("acc-1")

            verify(exactly = 0) { odooClient.clearCookies(any()) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // getSessionId() / getSessionCookies()
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class SessionAccess {

        @Test
        fun `Given server URL with https prefix when getSessionId then extracts host correctly`() {
            every { odooClient.getSessionId("my.odoo.com") } returns "session-abc"

            val sessionId = repository.getSessionId("https://my.odoo.com")

            assertEquals("session-abc", sessionId)
        }

        @Test
        fun `Given server URL with http prefix when getSessionId then extracts host correctly`() {
            every { odooClient.getSessionId("my.odoo.com") } returns "session-abc"

            val sessionId = repository.getSessionId("http://my.odoo.com")

            assertEquals("session-abc", sessionId)
        }

        @Test
        fun `Given server URL with path when getSessionId then uses host only`() {
            every { odooClient.getSessionId("my.odoo.com") } returns "session-abc"

            val sessionId = repository.getSessionId("https://my.odoo.com/web/login")

            assertEquals("session-abc", sessionId)
        }

        @Test
        fun `Given no session when getSessionId then returns null`() {
            every { odooClient.getSessionId(any()) } returns null

            assertNull(repository.getSessionId("https://unknown.com"))
        }
    }

    // ──────────────────────────────────────────────────────────
    // getAccountCount()
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given two accounts exist when getAccountCount then returns 2`() = runTest {
        coEvery { accountDao.getAccountCount() } returns 2

        assertEquals(2, repository.getAccountCount())
    }

    @Test
    fun `Given no accounts exist when getAccountCount then returns 0`() = runTest {
        coEvery { accountDao.getAccountCount() } returns 0

        assertEquals(0, repository.getAccountCount())
    }
}
