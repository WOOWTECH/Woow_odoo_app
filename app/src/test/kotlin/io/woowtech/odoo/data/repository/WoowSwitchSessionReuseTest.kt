package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.api.SessionOwnership
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Live regression 1001e W3 (demo111, 2026-10-02): a WOOW switch B→A→B signed in again on every switch and
 * left the replaced sessions valid on the server. Like Apporo, a WOOW switch reuses the target's known
 * session when the server proves it is that uid AND db; only otherwise it signs in, and the target's old
 * session is revoked after the new one is committed — only if it is positively that account's and no
 * other account holds it.
 */
class WoowSwitchSessionReuseTest {
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
    private val unknownOnce = mutableSetOf<String>()
    private var mint = 0

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        accounts.clear(); accounts["a"] = a; accounts["b"] = b
        every { api.publishSession(any(), any()) } answers { jar = secondArg() }
        every { api.getSessionId(any()) } answers { jar }
        coEvery { api.revokeSession(any(), any()) } answers { revoked += secondArg<String>(); valid -= secondArg<String>(); true }
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } answers {
            val sid = secondArg<String>(); val uid = thirdArg<Int>(); val db = arg<String>(3)
            when {
                unknownOnce.remove(sid) -> SessionOwnership.Unknown
                sid !in valid -> SessionOwnership.ProvenMismatch
                accounts.values.any { sid.startsWith("${it.id}-") && it.userId == uid && it.database == db } -> SessionOwnership.Belongs
                else -> SessionOwnership.ProvenMismatch
            }
        }
        // WOOW sign-in: the client stores THIS response's session in the native jar.
        coEvery { api.authenticate(any(), any(), any(), any()) } answers {
            val account = accounts.values.first { it.username == thirdArg<String>() }
            val sid = "${account.id}-sid-${++mint}"
            valid += sid; jar = sid
            AuthResult.Success(account.userId!!, sid, account.username, account.displayName)
        }
        coEvery { dao.findAccount(any(), any(), any()) } answers { accounts.values.firstOrNull { it.username == thirdArg<String>() } }
        coEvery { dao.getAccountById(any()) } answers { accounts[firstArg()]?.copy(isActive = active == firstArg<String>()) }
        coEvery { dao.getActiveAccountOnce() } answers { active?.let { accounts[it] }?.copy(isActive = true) }
        coEvery { dao.getAllAccountsList() } answers { accounts.values.map { it.copy(isActive = it.id == active) } }
        coEvery { dao.deactivateAllAccounts() } answers { active = null }
        coEvery { dao.insertAccount(any()) } answers {
            val account = firstArg<OdooAccount>(); accounts[account.id] = account
            if (account.isActive) active = account.id
        }
        coEvery { dao.activateAccount(any()) } answers { active = firstArg() }
        every { prefs.getPassword(any()) } returns "password-fixture"
        every { push.getStoredToken() } returns null
        coEvery { push.unregisterToken(any()) } returns Result.success(Unit)
        coEvery { push.reconcileOnAccountAvailable() } returns Result.success(Unit)
        repo = AccountRepository(dao, prefs, api, AppBrand.forCode("woowtech"), null, CoroutineScope(Dispatchers.Unconfined))
        repo.fcmTokenRepository = push
    }

    private suspend fun signIn(account: OdooAccount) =
        assertTrue(repo.authenticate(account.serverUrl, account.database, account.username, "password-fixture") is AuthResult.Success)

    @Test
    fun `Given both known sessions are valid when switching B to A to B then no new sign-in happens and nothing is revoked`() = runTest {
        signIn(a); signIn(b) // a-sid-1, b-sid-2; B active

        assertTrue(repo.switchAccount("a"))
        assertEquals("a-sid-1", jar)
        assertTrue(repo.switchAccount("b"))
        assertEquals("b-sid-2", jar)

        coVerify(exactly = 1) { api.authenticate(a.serverUrl, a.database, a.username, any()) }
        coVerify(exactly = 1) { api.authenticate(b.serverUrl, b.database, b.username, any()) }
        assertTrue(revoked.isEmpty(), "reused sessions are not revoked: $revoked")
        assertEquals("b", active)
    }

    @Test
    fun `Given A's known session expired when switching to A then A signs in again and the dead session is not destroyed on A's behalf`() = runTest {
        signIn(a); signIn(b)
        valid -= "a-sid-1"

        assertTrue(repo.switchAccount("a"))

        assertEquals("a-sid-3", jar)
        coVerify(exactly = 2) { api.authenticate(a.serverUrl, a.database, a.username, any()) }
        // An expired session is no longer provably A's (pi 1001d P1); there is nothing live left to orphan.
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun `Given A's session could not be proven at switch time but is still A's when switching to A then it is revoked after the new one is committed`() = runTest {
        signIn(a); signIn(b)
        unknownOnce += "a-sid-1" // e.g. a timeout during the reuse check

        assertTrue(repo.switchAccount("a"))

        assertEquals("a-sid-3", jar)
        assertEquals(listOf("a-sid-1"), revoked, "the replaced, still-valid session must not stay orphaned")
    }
}
