package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * iOS demo111 D5 parity (2026-09-30): an Apporo account switch reuses the target's session when the
 * server still proves it is that uid AND db, instead of signing in (a new server session and
 * res.users.log row) on every switch. A replaced session is revoked only after the replacement
 * committed; a superseded switch never revokes the target's stored session.
 */
class ApporoSessionReuseTest {
    private val a = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11)
    private val b = OdooAccount("b", "https://fixture.test", "db-b", "user-b", "B", userId = 22)
    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var api: OdooJsonRpcClient
    private lateinit var push: FcmTokenRepository
    private lateinit var repo: AccountRepository
    private val accounts = linkedMapOf<String, OdooAccount>()
    private var active: String? = null
    private var jar: String? = null
    private val revoked = mutableListOf<String>()
    private val valid = mutableSetOf<String>()
    private var mint = 0

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        accounts.clear(); accounts["a"] = a; accounts["b"] = b
        active = null; jar = null; revoked.clear(); valid.clear(); mint = 0
        every { api.publishApporoSession(any(), any()) } answers { jar = secondArg() }
        every { api.getSessionId(any()) } answers { jar }
        coEvery { api.revokeSession(any(), any()) } answers { revoked += secondArg<String>(); valid -= secondArg<String>(); true }
        coEvery { api.sessionBelongsTo(any(), any(), any(), any()) } answers {
            val sid = secondArg<String>(); val uid = thirdArg<Int>(); val db = arg<String>(3)
            sid in valid && accounts.values.any { sid.startsWith("${it.id}-") && it.userId == uid && it.database == db }
        }
        coEvery { dao.findAccount(any(), any(), any()) } answers { accounts.values.firstOrNull { it.username == thirdArg<String>() } }
        coEvery { dao.getAccountById(any()) } answers { accounts[firstArg()] }
        coEvery { dao.getActiveAccountOnce() } answers { active?.let { accounts[it] } }
        coEvery { dao.getAllAccountsList() } answers { accounts.values.toList() }
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
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } answers { success(thirdArg()) }
        repo = AccountRepository(dao, prefs, api, AppBrand.forCode("apporo"), null, CoroutineScope(Dispatchers.Unconfined))
        repo.fcmTokenRepository = push
    }

    private fun success(username: String): AuthResult.Success {
        val account = accounts.values.first { it.username == username }
        val sid = "${account.id}-sid-${++mint}"
        valid += sid
        return AuthResult.Success(account.userId!!, sid, account.username, account.displayName)
    }

    private suspend fun signIn(account: OdooAccount) =
        assertTrue(repo.authenticate(account.serverUrl, account.database, account.username, "password-fixture") is AuthResult.Success)

    @Test
    fun `Given B's session is still valid when switching back to B then no new sign-in happens`() = runTest {
        signIn(b); signIn(a) // B minted b-sid-1, A minted a-sid-2; A active
        assertTrue(repo.switchAccount("b"))

        coVerify(exactly = 1) { api.authenticateApporoIsolated(b.serverUrl, b.database, b.username, any()) }
        assertEquals("b", active)
        assertEquals("b-sid-1", jar)
        coVerify { push.onManualLogin("b", "b-sid-1") }
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun `Given B's session expired when switching to B then B signs in again and the stale session is revoked after commit`() = runTest {
        signIn(b); signIn(a)
        valid -= "b-sid-1" // expired on the server

        assertTrue(repo.switchAccount("b"))

        coVerify(exactly = 2) { api.authenticateApporoIsolated(b.serverUrl, b.database, b.username, any()) }
        assertEquals("b-sid-3", jar)
        assertEquals(listOf("b-sid-1"), revoked)
        io.mockk.coVerifyOrder {
            api.publishApporoSession(b.serverUrl, "b-sid-3")
            api.revokeSession(b.serverUrl, "b-sid-1")
        }
    }

    @Test
    fun `Given the stored session proves a different database then fail closed and sign in`() = runTest {
        signIn(b); signIn(a)
        coEvery { api.sessionBelongsTo(any(), "b-sid-1", any(), any()) } returns false // e.g. db missing / different

        assertTrue(repo.switchAccount("b"))

        coVerify(exactly = 2) { api.authenticateApporoIsolated(b.serverUrl, b.database, b.username, any()) }
        assertEquals("b-sid-3", jar)
    }

    @Test
    fun `Given a switch to B is superseded by a newer selection then B's stored session is not revoked`() = runTest {
        signIn(b); signIn(a)
        valid -= "b-sid-1" // forces B to sign in again, which we hold
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { api.authenticateApporoIsolated(b.serverUrl, b.database, b.username, any()) } coAnswers {
            started.complete(Unit); release.await(); success(b.username)
        }
        val older = async { repo.switchAccount("b") }
        started.await()
        assertTrue(repo.switchAccount("a")) // the user picked A while B was signing in
        release.complete(Unit)

        assertFalse(older.await())
        assertEquals("a", active)
        assertFalse("b-sid-1" in revoked) // the target's stored session survives a superseded switch
        assertTrue(revoked.single().startsWith("b-sid-")) // only the minted, never-published session
        verify(exactly = 0) { api.publishApporoSession(any(), match { it.startsWith("b-sid-") && it != "b-sid-1" }) }
    }

    @Test
    fun `Given A is displayed when B is added then A's WebView session is kept and switching back reuses it`() = runTest {
        // Adding an account replaces the displayed account's WebView session; without remembering it,
        // switching back signed A in again and orphaned that session (same class as D5).
        signIn(a) // a-sid-1, A displayed
        valid += "a-web-sid"
        repo.webDataCleaner = object : AccountWebDataCleaner {
            override suspend fun webViewSessionIdOf(accountId: String, serverUrl: String) =
                if (accountId == "a") "a-web-sid" else null
            override suspend fun removeAccountData(accountId: String, serverUrl: String, removal: WebDataRemoval) = Unit
        }
        signIn(b) // B added and displayed

        assertTrue(repo.switchAccount("a"))

        coVerify(exactly = 1) { api.authenticateApporoIsolated(a.serverUrl, a.database, a.username, any()) }
        assertEquals("a-web-sid", jar)
        assertTrue("a-web-sid" !in revoked)
    }

    @Test
    fun `Given a re-login of the same account then the replaced session is revoked after commit`() = runTest {
        signIn(a)
        signIn(a)
        assertEquals(listOf("a-sid-1"), revoked)
        assertEquals("a-sid-2", jar)
    }
}
