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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * pi 1001f: a WOOW switch / sign-in published the new session to the shared host jar BEFORE the selection
 * was committed and had no selection fence, so a late B could overwrite a newer C (or a failed commit could
 * leave A active with B's cookie). The session now reaches the jar only at one commit boundary (attempt still
 * current, target still exists, account rows written); losers keep the existing jar and revoke only their own
 * unpublished session; the target's replaced session is revoked only after a successful commit; a failed
 * switch re-registers the previous account's push device.
 */
class WoowSelectionFenceTest {
    private val a = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11)
    private val b = OdooAccount("b", "https://fixture.test", "db-b", "user-b", "B", userId = 22)
    private val c = OdooAccount("c", "https://fixture.test", "db-c", "user-c", "C", userId = 33)
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
    private val holdAuth = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val holdProof = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var failActivate: String? = null
    private var mint = 0

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        accounts.clear(); accounts["a"] = a; accounts["b"] = b; accounts["c"] = c
        every { api.publishSession(any(), any()) } answers { jar = secondArg() }
        every { api.getSessionId(any()) } answers { jar }
        coEvery { api.revokeSession(any(), any()) } answers { revoked += secondArg<String>(); valid -= secondArg<String>(); true }
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } coAnswers {
            val sid = secondArg<String>(); val uid = thirdArg<Int>(); val db = arg<String>(3)
            holdProof[sid]?.await()
            when {
                sid !in valid -> SessionOwnership.ProvenMismatch
                accounts.values.any { sid.startsWith("${it.id}-") && it.userId == uid && it.database == db } -> SessionOwnership.Belongs
                else -> SessionOwnership.ProvenMismatch
            }
        }
        // WOOW sign-in: returns THIS response's session and publishes nothing (pi 1001f P1).
        coEvery { api.authenticate(any(), any(), any(), any()) } coAnswers {
            val account = accounts.values.first { it.username == thirdArg<String>() }
            holdAuth[account.id]?.await()
            val sid = "${account.id}-sid-${++mint}"
            valid += sid
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
        coEvery { dao.activateAccount(any()) } answers {
            if (firstArg<String>() == failActivate) throw IllegalStateException("row write failed")
            active = firstArg()
        }
        every { prefs.getPassword(any()) } returns "password-fixture"
        every { push.getStoredToken() } returns "token-fixture"
        coEvery { push.registerToken(any(), any()) } returns Result.success(Unit)
        coEvery { push.unregisterToken(any()) } returns Result.success(Unit)
        coEvery { push.reconcileOnAccountAvailable() } returns Result.success(Unit)
        repo = AccountRepository(dao, prefs, api, AppBrand.forCode("woowtech"), null, CoroutineScope(Dispatchers.Unconfined))
        repo.fcmTokenRepository = push
    }

    private suspend fun signIn(account: OdooAccount) =
        assertTrue(repo.authenticate(account.serverUrl, account.database, account.username, "password-fixture") is AuthResult.Success)

    @Test
    fun `Given B's reuse check is held when C is chosen then the late B neither takes the jar nor the active account`() = runTest {
        signIn(b); signIn(c); signIn(a) // b-sid-1, c-sid-2, a-sid-3; A active, A's session in the jar
        val gate = CompletableDeferred<Unit>().also { holdProof["b-sid-1"] = it }

        val lateB = async { repo.switchAccount("b") }
        advanceUntilIdle()
        assertTrue(repo.switchAccount("c"))
        gate.complete(Unit)

        assertFalse(lateB.await(), "a superseded switch must not succeed")
        assertEquals("c", active)
        assertEquals("c-sid-2", jar, "the newer selection keeps the jar")
        assertFalse("b-sid-1" in revoked, "B's own live session is not revoked by a lost switch")
    }

    @Test
    fun `Given B's sign-in is held when C is chosen then the late B revokes only its own unpublished session`() = runTest {
        signIn(b); signIn(c); signIn(a)
        valid -= "b-sid-1" // forces B to sign in again
        val gate = CompletableDeferred<Unit>().also { holdAuth["b"] = it }

        val lateB = async { repo.switchAccount("b") }
        advanceUntilIdle()
        assertTrue(repo.switchAccount("c"))
        gate.complete(Unit)

        assertFalse(lateB.await())
        assertEquals("c", active)
        assertEquals("c-sid-2", jar)
        assertEquals(listOf("b-sid-4"), revoked, "only the late B's own fresh session is revoked")
    }

    @Test
    fun `Given A's sign-in is held when B is chosen then the late A sign-in neither takes the jar nor the active account`() = runTest {
        signIn(b); signIn(a) // b-sid-1, a-sid-2
        valid -= "a-sid-2"
        assertTrue(repo.switchAccount("b"))
        val gate = CompletableDeferred<Unit>().also { holdAuth["a"] = it }

        val lateA = async { repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") }
        advanceUntilIdle()
        assertTrue(repo.switchAccount("b") || active == "b")
        gate.complete(Unit)

        assertTrue(lateA.await() is AuthResult.Error, "a superseded sign-in must not succeed")
        assertEquals("b", active)
        assertEquals("b-sid-1", jar)
    }

    @Test
    fun `Given the account rows cannot be written when switching to B then A stays with its jar and B's old session is kept`() = runTest {
        signIn(b); signIn(a)
        valid -= "b-sid-1"; valid += "b-sid-1-still" // B re-signs in; nothing of B is provably replaced yet
        failActivate = "b"

        val switched = runCatching { repo.switchAccount("b") }.getOrDefault(false)

        assertFalse(switched)
        assertEquals("a", active, "the previous account stays active")
        assertEquals("a-sid-2", jar, "the jar is not handed to B without a committed selection")
        assertTrue(revoked.all { it.startsWith("b-sid-3") }, "only B's own unpublished session may be revoked: $revoked")
    }

    @Test
    fun `Given B's stored session is still B's when the commit fails then it is not revoked`() = runTest {
        signIn(b); signIn(a)
        coEvery { api.sessionOwnership(any(), "b-sid-1", any(), any()) } returnsMany
            listOf(SessionOwnership.Unknown, SessionOwnership.Belongs, SessionOwnership.Belongs)
        failActivate = "b"

        runCatching { repo.switchAccount("b") }

        assertFalse("b-sid-1" in revoked, "the replaced session is revoked only after a successful commit")
    }

    @Test
    fun `Given a switch to B fails after A's push device was unregistered then A is registered again`() = runTest {
        signIn(b); signIn(a)
        valid -= "b-sid-1"
        coEvery { api.authenticate(any(), any(), "user-b", any()) } returns
            AuthResult.Error("Bad gateway", AuthResult.ErrorType.SERVER_ERROR, httpStatus = 502)

        assertFalse(repo.switchAccount("b"))

        coVerify { push.unregisterToken("a") }
        coVerify { push.registerToken("a", "token-fixture") }
        assertEquals("a", active)
    }

    @Test
    fun `Given a switch to B is superseded by C when B fails late then A is not registered again`() = runTest {
        signIn(b); signIn(c); signIn(a)
        valid -= "b-sid-1"
        val gate = CompletableDeferred<Unit>().also { holdAuth["b"] = it }

        val lateB = async { repo.switchAccount("b") }
        advanceUntilIdle()
        assertTrue(repo.switchAccount("c"))
        gate.complete(Unit)

        assertFalse(lateB.await())
        coVerify(exactly = 0) { push.registerToken("a", any()) }
        coVerify { push.registerToken("c", "token-fixture") }
    }
}
