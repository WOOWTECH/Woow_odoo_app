package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.api.SessionOwnership
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.local.KnownSessionStore
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * pi 1001b Android P2: the known-session record was memory-only, so after A→B on one host and an app
 * restart, logging out B could neither hand A back its still-valid session nor revoke it — A's WebView
 * signed in again and the original stayed valid on the server. The record must survive the restart
 * (encrypted store); an unproven one is revoked best-effort unless another account holds it.
 */
class WoowPromotionAfterRestartTest {
    private val a = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11)
    private val b = OdooAccount("b", "https://fixture.test", "db-b", "user-b", "B", userId = 22)
    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var api: OdooJsonRpcClient
    private lateinit var push: FcmTokenRepository
    private val store = KnownSessionStore.InMemory()
    private val webSessions = mutableMapOf<String, String>()
    private var webOwner: String? = null
    private val accounts = linkedMapOf<String, OdooAccount>()
    private var active: String? = null
    private var jar: String? = null
    private val revoked = mutableListOf<String>()
    private val published = mutableListOf<String>()

    /** When set, WebView data removal waits for it (cancellation injection, pi 1001c P2). */
    private var removalGate: CompletableDeferred<Unit>? = null

    private val cleaner = object : AccountWebDataCleaner {
        override suspend fun webViewSessionIdOf(accountId: String, serverUrl: String): String? =
            webSessions[accountId]?.takeIf { webOwner == accountId }
        override suspend fun removeAccountData(accountId: String, serverUrl: String, removal: WebDataRemoval) {
            removalGate?.await()
        }
    }

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        every { api.publishSession(any(), any()) } answers { jar = secondArg(); published += secondArg<String>() }
        every { api.getSessionId(any()) } answers { jar }
        every { api.clearCookies(any()) } answers { jar = null }
        coEvery { api.revokeSession(any(), any()) } answers { revoked += secondArg<String>(); true }
        coEvery { api.authenticate(any(), any(), any(), any()) } answers {
            val account = accounts.values.first { it.username == thirdArg<String>() }
            val sid = "${account.id}-sid-fixture"
            jar = sid
            AuthResult.Success(account.userId!!, sid, account.username, account.displayName)
        }
        coEvery { dao.findAccount(any(), any(), any()) } answers { accounts.values.firstOrNull { it.username == thirdArg<String>() } }
        coEvery { dao.getAccountById(any()) } answers { accounts[firstArg()]?.copy(isActive = active == firstArg<String>()) }
        coEvery { dao.getActiveAccountOnce() } answers { active?.let { accounts[it] }?.copy(isActive = true) }
        coEvery { dao.getAllAccountsList() } answers { accounts.values.map { it.copy(isActive = it.id == active) } }
        coEvery { dao.deactivateAllAccounts() } answers { active = null }
        coEvery { dao.deleteAccountById(any()) } answers { accounts.remove(firstArg<String>()); Unit }
        coEvery { dao.insertAccount(any()) } answers {
            val account = firstArg<OdooAccount>(); accounts[account.id] = account
            if (account.isActive) active = account.id
        }
        coEvery { dao.activateAccount(any()) } answers { active = firstArg() }
        every { prefs.getPassword(any()) } returns "password-fixture"
        coEvery { push.unregisterToken(any()) } returns Result.success(Unit)
        coEvery { push.reconcileOnAccountAvailable() } returns Result.success(Unit)
        every { push.getStoredToken() } returns null
        accounts["a"] = a; accounts["b"] = b
    }

    /** A fresh repository over the same durable store = the app after a process restart. */
    private fun repo() =
        AccountRepository(dao, prefs, api, AppBrand.forCode("woowtech"), null, CoroutineScope(Dispatchers.Unconfined)).also {
            it.fcmTokenRepository = push
            it.webDataCleaner = cleaner
            it.knownSessionStore = store
        }

    private suspend fun aThenBThenRestart(): AccountRepository {
        val before = repo()
        before.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        webOwner = "a"; webSessions["a"] = "a-sid-fixture"
        before.authenticate(b.serverUrl, b.database, b.username, "password-fixture")
        webOwner = "b"; webSessions["b"] = "b-sid-fixture"
        return repo()
    }

    @Test
    fun `Given A then B and an app restart when B logs out and A's session is still A's then A gets it back without a new sign-in`() = runTest {
        val afterRestart = aThenBThenRestart()
        coEvery { api.sessionOwnership(a.serverUrl, "a-sid-fixture", 11, "db-a") } returns SessionOwnership.Belongs

        afterRestart.logout("b")

        assertEquals("a", active)
        assertEquals(listOf("a-sid-fixture"), published, "the restart must not lose A's still-valid session")
        assertFalse("a-sid-fixture" in revoked)
        coVerify(exactly = 1) { api.authenticate(a.serverUrl, a.database, a.username, any()) }
    }

    @Test
    fun `Given the server cannot be asked (offline, timeout) when B logs out then A's session is neither revoked, published nor forgotten`() = runTest {
        // pi 1001c P1: "cannot prove it is A's" is no evidence that it is dead — it may be A's live session.
        val afterRestart = aThenBThenRestart()
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.Unknown

        afterRestart.logout("b")

        assertEquals("a", active)
        assertFalse("a-sid-fixture" in revoked, "an unproven session must never be revoked")
        verify(exactly = 0) { api.publishSession(any(), "a-sid-fixture") }
        assertEquals("a-sid-fixture", store.load("a"), "the record is kept to be proven later")
    }

    @Test
    fun `Given the server proves A's remembered session is someone else's or expired when B logs out then it is forgotten but never revoked`() = runTest {
        // pi 1001c P1: a different uid/db may mean the SID now belongs to someone else; destroy acts on the SID alone.
        val afterRestart = aThenBThenRestart()
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.ProvenMismatch

        afterRestart.logout("b")

        assertEquals("a", active)
        verify(exactly = 0) { api.publishSession(any(), "a-sid-fixture") }
        assertFalse("a-sid-fixture" in revoked, "a session that is not A's must not be destroyed on A's behalf")
        assertNull(store.load("a"), "a session proven not A's is forgotten")
    }

    @Test
    fun `Given A's remembered session is the one a sibling holds when B logs out then it is not revoked`() = runTest {
        val afterRestart = aThenBThenRestart()
        val c = OdooAccount("c", "https://fixture.test", "db-c", "user-c", "C", userId = 33)
        accounts["c"] = c
        store.save("c", "a-sid-fixture")
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.ProvenMismatch

        afterRestart.logout("b")

        assertFalse("a-sid-fixture" in revoked)
        assertEquals("a-sid-fixture", store.load("c"), "the sibling's record is untouched")
    }

    @Test
    fun `Given sessions are recorded then they live only in the known-session store and logout clears them`() = runTest {
        val afterRestart = aThenBThenRestart()
        assertEquals("a-sid-fixture", store.load("a"))
        assertEquals("b-sid-fixture", store.load("b"))
        verify(exactly = 0) { prefs.savePassword(any(), match { it.contains("sid") }) }

        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.Belongs
        afterRestart.logout("b")

        assertNull(store.load("b"), "logout forgets the logged-out account's session")
    }

    @Test
    fun `Given logout is cancelled while the WebView data is being removed then the account and its session record go together`() = runTest {
        // pi 1001c P2: the record must not be forgotten while the account survives the cancellation.
        val repo = aThenBThenRestart()
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.Belongs
        val gate = CompletableDeferred<Unit>().also { removalGate = it }

        val job = launch { repo.logout("b") }
        advanceUntilIdle()
        job.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        val accountGone = "b" !in accounts
        val recordGone = store.load("b") == null
        assertEquals(accountGone, recordGone, "account deleted=$accountGone but record forgotten=$recordGone")
        assertTrue(accountGone, "once started, the logout boundary completes")
    }

    @Test
    fun `Given a self-heal for A finishes after A was logged out then nothing is published or recorded and its session is revoked`() = runTest {
        // pi 1001c/1001d P2: a late heal must not leave a session secret no account can clean up.
        val repo = aThenBThenRestart()
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.Belongs
        val ticket = repo.beginHeal("a")
        repo.logout("a")
        val jarBefore = jar

        assertFalse(repo.commitHeal("a", a.serverUrl, "a-heal-sid", ticket))

        assertNull(store.load("a"), "a deleted account gets no session record")
        assertEquals(jarBefore, jar, "the active B keeps its jar session")
        assertTrue("a-heal-sid" in revoked, "the heal's own unused session is revoked")
    }

    @Test
    fun `Given a heal for A is in flight when A signs in manually then the old heal cannot replace the winner`() = runTest {
        // pi 1001d P1: forced order — heal waits, manual login wins, old heal returns.
        val repo = aThenBThenRestart()
        val ticket = repo.beginHeal("a")
        repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") // winner: a-sid-fixture

        assertFalse(repo.commitHeal("a", a.serverUrl, "a-heal-sid", ticket))

        assertEquals("a-sid-fixture", jar, "the jar keeps the manual login's session")
        assertEquals("a-sid-fixture", store.load("a"), "the record keeps the manual login's session")
        assertTrue("a-heal-sid" in revoked)
    }

    @Test
    fun `Given a heal for A is in flight when the user switches to B on the same host then the old heal cannot take B's jar`() = runTest {
        val repo = aThenBThenRestart()
        repo.switchAccount("a") // A displayed
        val ticket = repo.beginHeal("a")
        repo.switchAccount("b") // winner: B's session in the jar

        assertFalse(repo.commitHeal("a", a.serverUrl, "a-heal-sid", ticket))

        assertEquals("b-sid-fixture", jar, "the displayed B keeps its own session")
        assertTrue("a-heal-sid" in revoked)
    }

    @Test
    fun `Given A is being logged out when a heal for A completes during the WebView cleanup then it cannot commit and is revoked`() = runTest {
        // pi 1001d P1: the deletion window — the heal must not record a session the cleanup will never revoke.
        val repo = aThenBThenRestart()
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.Belongs
        val gate = CompletableDeferred<Unit>().also { removalGate = it }
        val before = repo.beginHeal("a")

        val logout = launch { repo.logout("a") }
        advanceUntilIdle() // logout is now awaiting the WebView cleanup
        val during = repo.beginHeal("a")
        val committedOld = repo.commitHeal("a", a.serverUrl, "a-heal-1", before)
        val committedNew = repo.commitHeal("a", a.serverUrl, "a-heal-2", during)
        gate.complete(Unit)
        logout.join()

        assertFalse(committedOld); assertFalse(committedNew)
        assertTrue("a-heal-1" in revoked && "a-heal-2" in revoked, "sessions created in the window are revoked: $revoked")
        assertNull(store.load("a"))
    }

    @Test
    fun `Given a heal for a current account with no newer selection then it is published and recorded`() = runTest {
        val repo = aThenBThenRestart()
        val ticket = repo.beginHeal("b")

        assertTrue(repo.commitHeal("b", b.serverUrl, "b-heal-sid", ticket))

        assertEquals("b-heal-sid", jar)
        assertEquals("b-heal-sid", store.load("b"))
        assertFalse("b-heal-sid" in revoked)
    }

    @Test
    fun `Given B's record removal never reached the disk when the app restarts then the orphan record is removed`() = runTest {
        // pi 1001d P2: the RAM retry dies with the process; the cleanup must survive a restart.
        val disk = object : KnownSessionStore {
            val values = HashMap<String, String>()
            var removalsFail = false
            override fun load(accountId: String): String? = values[accountId]
            override fun save(accountId: String, sessionId: String) { values[accountId] = sessionId }
            override fun remove(accountId: String) { check(!removalsFail) { "write not committed" }; values.remove(accountId) }
            override fun accountIds(): Set<String> = values.keys.toSet()
        }
        val before = repo().also { it.knownSessionStore = disk }
        before.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        before.authenticate(b.serverUrl, b.database, b.username, "password-fixture")
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.Belongs
        disk.removalsFail = true
        before.logout("b")
        assertEquals("b-sid-fixture", disk.values["b"], "precondition: the removal did not reach the disk")

        disk.removalsFail = false
        repo().also { it.knownSessionStore = disk } // app restart

        assertNull(disk.values["b"], "a record without an account is removed at startup")
        assertEquals("a-sid-fixture", disk.values["a"], "a live account's record is kept")
    }
}
