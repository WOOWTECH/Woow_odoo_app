package io.woowtech.odoo.data.repository

import io.mockk.*
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Phase 3: active account commit and response-specific UI SID publication share a selection fence. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApporoAccountSelectionTest {
    private val a = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11)
    private val b = OdooAccount("b", "https://fixture.test", "db-b", "user-b", "B", userId = 22)
    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var api: OdooJsonRpcClient
    private lateinit var push: FcmTokenRepository
    private lateinit var repo: AccountRepository
    private var active: String? = null
    private var cookie = "b-sid-fixture"
    private val accounts = linkedMapOf<String, OdooAccount>()

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        accounts.clear(); accounts["a"] = a; accounts["b"] = b; active = null
        cookie = "b-sid-fixture"
        every { api.publishApporoSession(any(), any()) } answers { cookie = secondArg() }
        coEvery { dao.deleteAccountById(any()) } answers { accounts.remove(firstArg<String>()); Unit }
        coEvery { dao.findAccount(any(), any(), any()) } answers { accounts.values.firstOrNull { it.username == thirdArg<String>() } }
        coEvery { dao.getAccountById(any()) } answers { accounts[firstArg()] }
        coEvery { dao.getActiveAccountOnce() } answers { active?.let { accounts[it] } }
        coEvery { dao.deactivateAllAccounts() } answers { active = null }
        coEvery { dao.insertAccount(any()) } answers {
            val account = firstArg<OdooAccount>(); accounts[account.id] = account
            if (account.isActive) active = account.id
        }
        coEvery { dao.activateAccount(any()) } answers { active = firstArg() }
        every { prefs.getPassword(any()) } returns "password-fixture"
        every { push.getStoredToken() } returns "token-fixture"
        coEvery { push.registerToken(any(), any()) } returns Result.success(Unit)
        coEvery { push.unregisterToken(any()) } returns Result.success(Unit)
        coEvery { push.reconcileOnAccountAvailable() } returns Result.success(Unit)
        repo = AccountRepository(dao, prefs, api, AppBrand.forCode("apporo"))
        repo.fcmTokenRepository = push
    }

    private fun success(account: OdooAccount) = AuthResult.Success(account.userId!!, "${account.id}-sid-fixture", account.username, account.displayName)

    @Test
    fun `Given slow A login then newer B login when A completes late then active and SID both remain B`() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { api.authenticateApporoIsolated(a.serverUrl, a.database, a.username, any()) } coAnswers {
            started.complete(Unit); release.await(); success(a)
        }
        coEvery { api.authenticateApporoIsolated(b.serverUrl, b.database, b.username, any()) } returns success(b)
        val older = async { repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") }
        started.await()
        assertTrue(repo.authenticate(b.serverUrl, b.database, b.username, "password-fixture") is AuthResult.Success)
        release.complete(Unit)
        assertTrue(older.await() is AuthResult.Error)
        assertEquals("b", active)
        verify(exactly = 1) { api.publishApporoSession(b.serverUrl, "b-sid-fixture") }
        verify(exactly = 0) { api.publishApporoSession(any(), "a-sid-fixture") }
        verify(exactly = 0) { prefs.savePassword("a", any()) }
        coVerify(exactly = 1) { push.onManualLogin("b", "b-sid-fixture") }
        coVerify(exactly = 0) { push.onManualLogin("a", any()) }
    }

    @Test
    fun `Given slow A login when newer B switch finishes then late A cannot alter active or cookie`() = runTest {
        active = "a"
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { api.authenticateApporoIsolated(a.serverUrl, a.database, a.username, any()) } coAnswers {
            started.complete(Unit); release.await(); success(a)
        }
        coEvery { api.authenticateApporoIsolated(b.serverUrl, b.database, b.username, any()) } returns success(b)
        val older = async { repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") }
        started.await()
        assertTrue(repo.switchAccount("b"))
        release.complete(Unit)
        assertTrue(older.await() is AuthResult.Error)
        assertEquals("b", active)
        coVerifyOrder {
            push.unregisterToken("a")
            api.authenticateApporoIsolated(b.serverUrl, b.database, b.username, "password-fixture")
            dao.activateAccount("b")
            api.publishApporoSession(b.serverUrl, "b-sid-fixture")
            push.onManualLogin("b", "b-sid-fixture")
            push.registerToken("b", "token-fixture")
        }
        verify(exactly = 0) { api.publishApporoSession(any(), "a-sid-fixture") }
    }

    @Test
    fun `Given successful manual login then push receives exact response SID with one authentication`() = runTest {
        coEvery { api.authenticateApporoIsolated(a.serverUrl, a.database, a.username, any()) } returns success(a)
        assertTrue(repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") is AuthResult.Success)
        coVerify(exactly = 1) { api.authenticateApporoIsolated(any(), any(), any(), any()) }
        coVerifyOrder {
            dao.insertAccount(any())
            api.publishApporoSession(a.serverUrl, "a-sid-fixture")
            push.onManualLogin("a", "a-sid-fixture")
            push.reconcileOnAccountAvailable()
        }
    }

    @Test
    fun `Given uid success without SID then preserve B and perform zero account credential or cookie mutations`() = runTest {
        active = "b"
        coEvery { api.authenticateApporoIsolated(a.serverUrl, a.database, a.username, any()) } returns success(a).copy(sessionId = "")
        val result = repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        assertEquals(AuthResult.ErrorType.SESSION_EXPIRED, (result as AuthResult.Error).type)
        assertEquals("b", active)
        coVerify(exactly = 0) { dao.insertAccount(any()) }
        coVerify(exactly = 0) { dao.deactivateAllAccounts() }
        coVerify(exactly = 0) { dao.activateAccount(any()) }
        verify(exactly = 0) { prefs.savePassword(any(), any()) }
        verify(exactly = 0) { api.publishApporoSession(any(), any()) }
        coVerify(exactly = 0) { push.onManualLogin(any(), any()) }
        coVerify(exactly = 0) { push.reconcileOnAccountAvailable() }
        coVerify(exactly = 1) { api.authenticateApporoIsolated(any(), any(), any(), any()) }
    }

    @Test
    fun `Given switch response has no proven SID then do not activate A or publish over B`() = runTest {
        active = "b"
        coEvery { api.authenticateApporoIsolated(a.serverUrl, a.database, a.username, any()) } returns success(a).copy(sessionId = "")
        assertFalse(repo.switchAccount("a"))
        assertEquals("b", active)
        coVerify(exactly = 0) { dao.deactivateAllAccounts() }
        coVerify(exactly = 0) { dao.activateAccount(any()) }
        verify(exactly = 0) { api.publishApporoSession(any(), any()) }
        verify(exactly = 0) { prefs.savePassword(any(), any()) }
    }


    @Test
    fun `Given cancellation after switch activation then finish local SID commit before cancellation escapes`() = runTest {
        active = "b"
        val activated = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } returns success(a)
        coEvery { dao.updateLastLogin("a", any()) } coAnswers {
            assertEquals("a", active)
            activated.complete(Unit); release.await()
        }
        val job = launch { repo.switchAccount("a") }
        activated.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("a", active)
        assertEquals("a-sid-fixture", cookie)
        coVerify(exactly = 0) { push.registerToken(any(), any()) }
    }

    @Test
    fun `Given cancellation after manual insertion then finish local credential and SID commit`() = runTest {
        active = "b"
        val inserted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } returns success(a)
        coEvery { dao.insertAccount(any()) } coAnswers {
            val account = firstArg<OdooAccount>(); accounts[account.id] = account; active = account.id
            inserted.complete(Unit); release.await()
        }
        val job = launch { repo.authenticate(a.serverUrl, a.database, a.username, "new-password-fixture") }
        inserted.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(job.isCancelled)
        assertEquals("a", active)
        assertEquals("a-sid-fixture", cookie)
        verify(exactly = 1) { prefs.savePassword("a", "new-password-fixture") }
        coVerify(exactly = 0) { push.reconcileOnAccountAvailable() }
    }

    @Test
    fun `Given cancellation before commit then no local mutation`() = runTest {
        active = "b"
        val checking = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } returns success(a)
        coEvery { dao.findAccount(any(), any(), any()) } coAnswers {
            checking.complete(Unit); release.await(); a
        }
        val job = launch { repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") }
        checking.await(); job.cancel(); job.join()
        assertEquals("b", active); assertEquals("b-sid-fixture", cookie)
        coVerify(exactly = 0) { dao.deactivateAllAccounts() }
        verify(exactly = 0) { prefs.savePassword(any(), any()) }
    }

    @Test
    fun `Given last-login local failure after activation then restore previous active and target row without publishing`() = runTest {
        active = "b"
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } returns success(a)
        coEvery { dao.updateLastLogin("a", any()) } throws IllegalStateException("local fixture failure")
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.switchAccount("a") } }
        assertEquals("b", active); assertEquals("b-sid-fixture", cookie); assertEquals(a, accounts["a"])
        verify(exactly = 0) { api.publishApporoSession(any(), any()) }
        coVerify(exactly = 0) { push.onManualLogin(any(), any()) }
    }

    @Test
    fun `Given credential write failure after new account insertion then remove row and restore previous active`() = runTest {
        active = "b"; accounts.remove("a")
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } returns success(a)
        every { prefs.getPassword(any()) } returns null
        every { prefs.savePassword(any(), any()) } throws IllegalStateException("local fixture failure")
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking {
            repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        } }
        assertEquals(setOf("b"), accounts.keys)
        assertEquals("b", active); assertEquals("b-sid-fixture", cookie)
        verify(exactly = 1) { prefs.removePassword(any()) }
        verify(exactly = 0) { api.publishApporoSession(any(), any()) }
    }

    @Test
    fun `Given push reset is waiting after commit then cancellation is not masked`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } returns success(a)
        coEvery { push.onManualLogin(any(), any()) } coAnswers { waiting.complete(Unit); CompletableDeferred<Unit>().await() }
        val job = launch { repo.switchAccount("a") }
        waiting.await(); job.cancel(); runCurrent()
        assertTrue(job.isCompleted)
        assertEquals("a", active); assertEquals("a-sid-fixture", cookie)
        coVerify(exactly = 0) { push.registerToken(any(), any()) }
    }
}
