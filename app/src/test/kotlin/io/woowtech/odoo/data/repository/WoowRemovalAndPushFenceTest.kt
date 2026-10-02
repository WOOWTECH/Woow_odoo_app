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
 * pi 1001g. P1: a WOOW logout / removal must supersede sign-ins in flight and tombstone the identity, so a
 * held sign-in of B that returns after (or started during) B's removal never recreates B. P2: the push
 * re-registration a failed switch owes the previous account is owned by the account finally displayed — not
 * by the attempt that unregistered it — and runs outside the selection lock.
 */
class WoowRemovalAndPushFenceTest {
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
    private val holdUnregister = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val holdRegister = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val failAuth = mutableSetOf<String>()
    private val registered = mutableListOf<String>()
    private var mint = 0

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        accounts.clear(); accounts["a"] = a; accounts["b"] = b; accounts["c"] = c
        every { api.publishSession(any(), any()) } answers { jar = secondArg() }
        every { api.getSessionId(any()) } answers { jar }
        every { api.clearCookies(any()) } answers { jar = null }
        coEvery { api.revokeSession(any(), any()) } answers { revoked += secondArg<String>(); valid -= secondArg<String>(); true }
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } answers {
            val sid = secondArg<String>(); val uid = thirdArg<Int>(); val db = arg<String>(3)
            if (sid in valid && accounts.values.any { sid.startsWith("${it.id}-") && it.userId == uid && it.database == db }) {
                SessionOwnership.Belongs
            } else SessionOwnership.ProvenMismatch
        }
        // WOOW sign-in: returns THIS response's session and publishes nothing (pi 1001f P1).
        coEvery { api.authenticate(any(), any(), any(), any()) } coAnswers {
            val user = thirdArg<String>()
            val id = user.removePrefix("user-")
            holdAuth[id]?.await()
            if (id in failAuth) return@coAnswers AuthResult.Error("Bad gateway", AuthResult.ErrorType.SERVER_ERROR, httpStatus = 502)
            val userId = mapOf("a" to 11, "b" to 22, "c" to 33).getValue(id)
            val sid = "$id-sid-${++mint}"
            valid += sid
            AuthResult.Success(userId, sid, user, id.uppercase())
        }
        coEvery { dao.findAccount(any(), any(), any()) } answers { accounts.values.firstOrNull { it.username == thirdArg<String>() } }
        coEvery { dao.getAccountById(any()) } answers { accounts[firstArg()]?.copy(isActive = active == firstArg<String>()) }
        coEvery { dao.getActiveAccountOnce() } answers { active?.let { accounts[it] }?.copy(isActive = true) }
        coEvery { dao.getAllAccountsList() } answers { accounts.values.map { it.copy(isActive = it.id == active) } }
        coEvery { dao.deactivateAllAccounts() } answers { active = null }
        coEvery { dao.deleteAccountById(any()) } answers {
            accounts.remove(firstArg<String>()); if (active == firstArg<String>()) active = null; Unit
        }
        coEvery { dao.insertAccount(any()) } answers {
            val account = firstArg<OdooAccount>(); accounts[account.id] = account
            if (account.isActive) active = account.id
        }
        coEvery { dao.activateAccount(any()) } answers { active = firstArg() }
        every { prefs.getPassword(any()) } returns "password-fixture"
        every { push.getStoredToken() } returns "token-fixture"
        coEvery { push.registerToken(any(), any()) } coAnswers {
            holdRegister[firstArg()]?.await()
            registered += firstArg<String>()
            Result.success(Unit)
        }
        coEvery { push.unregisterToken(any()) } coAnswers { holdUnregister[firstArg()]?.await(); Result.success(Unit) }
        coEvery { push.reconcileOnAccountAvailable() } returns Result.success(Unit)
        repo = AccountRepository(dao, prefs, api, AppBrand.forCode("woowtech"), null, CoroutineScope(Dispatchers.Unconfined))
        repo.fcmTokenRepository = push
    }

    private suspend fun signIn(account: OdooAccount) = repo.authenticate(account.serverUrl, account.database, account.username, "password-fixture")

    private fun assertBNotRecreated(late: AuthResult) {
        assertTrue(late is AuthResult.Error, "a sign-in overtaken by B's removal must not succeed: $late")
        assertFalse(accounts.values.any { it.username == "user-b" }, "B must not be recreated")
        assertEquals("a", active)
        assertTrue(jar!!.startsWith("a-"), "the jar keeps A's session")
        assertTrue(revoked.contains("b-sid-3"), "only the late sign-in's own unpublished session is revoked: $revoked")
    }

    // ---- P1: a sign-in started BEFORE the removal

    @Test
    fun `Given B's sign-in is held when B is logged out then the late response does not recreate B`() = runTest {
        signIn(b); signIn(a) // b-sid-1, a-sid-2
        val gate = CompletableDeferred<Unit>().also { holdAuth["b"] = it }
        val late = async { signIn(b) }
        advanceUntilIdle()

        repo.logout("b")
        gate.complete(Unit)

        assertBNotRecreated(late.await())
    }

    @Test
    fun `Given B's sign-in is held when B is removed then the late response does not recreate B`() = runTest {
        signIn(b); signIn(a)
        val gate = CompletableDeferred<Unit>().also { holdAuth["b"] = it }
        val late = async { signIn(b) }
        advanceUntilIdle()

        repo.removeAccount("b")
        gate.complete(Unit)

        assertBNotRecreated(late.await())
    }

    // ---- P1: a sign-in started DURING the removal

    @Test
    fun `Given B's logout is held when B signs in during it then the response after the logout does not recreate B`() = runTest {
        signIn(b); signIn(a)
        val removal = CompletableDeferred<Unit>().also { holdUnregister["b"] = it }
        val logout = async { repo.logout("b") }
        advanceUntilIdle()
        val auth = CompletableDeferred<Unit>().also { holdAuth["b"] = it }
        val late = async { signIn(b) }
        advanceUntilIdle()

        removal.complete(Unit)
        logout.await()
        auth.complete(Unit)

        assertBNotRecreated(late.await())
    }

    @Test
    fun `Given B's removal is held when B signs in during it then the response after the removal does not recreate B`() = runTest {
        signIn(b); signIn(a)
        val removal = CompletableDeferred<Unit>().also { holdUnregister["b"] = it }
        val remove = async { repo.removeAccount("b") }
        advanceUntilIdle()
        val auth = CompletableDeferred<Unit>().also { holdAuth["b"] = it }
        val late = async { signIn(b) }
        advanceUntilIdle()

        removal.complete(Unit)
        remove.await()
        auth.complete(Unit)

        assertBNotRecreated(late.await())
    }

    @Test
    fun `Given B was removed when B signs in again afterwards then the new sign-in succeeds`() = runTest {
        signIn(b); signIn(a)
        repo.removeAccount("b")

        val again = signIn(b)

        assertTrue(again is AuthResult.Success)
        assertEquals("user-b", accounts[active]!!.username)
    }

    // ---- P2: compensation owned by the finally displayed account

    @Test
    fun `Given B unregistered A when a later C fails early and B fails late then A's push device is registered again`() = runTest {
        signIn(b); signIn(a)
        valid -= "b-sid-1"; failAuth += "b"
        val gate = CompletableDeferred<Unit>().also { holdAuth["b"] = it }
        val lateB = async { repo.switchAccount("b") }
        advanceUntilIdle()
        coVerify { push.unregisterToken("a") }

        assertFalse(repo.switchAccount("missing"), "C fails before it touches A")
        gate.complete(Unit)

        assertFalse(lateB.await())
        assertEquals("a", active)
        assertTrue("a" in registered, "A is displayed again and must get its push device back")
    }

    // ---- P2: the re-registration runs outside the selection lock

    @Test
    fun `Given A's re-registration is held when a newer sign-in starts then it commits without waiting`() = runTest {
        signIn(b); signIn(c); signIn(a)
        valid -= "b-sid-1"; failAuth += "b"
        val register = CompletableDeferred<Unit>().also { holdRegister["a"] = it }
        assertFalse(repo.switchAccount("b"))

        val newer = async { signIn(c) }
        advanceUntilIdle()

        assertTrue(newer.isCompleted, "the held re-registration must not hold the selection lock")
        assertEquals("c", active)
        register.complete(Unit)
        assertTrue("a" in registered)
    }

    @Test
    fun `Given A's re-registration is held when switching away from A then the switch commits and A stays unregistered`() = runTest {
        signIn(b); signIn(c); signIn(a)
        valid -= "b-sid-1"; failAuth += "b"
        val register = CompletableDeferred<Unit>().also { holdRegister["a"] = it }
        assertFalse(repo.switchAccount("b"))

        val away = async { repo.switchAccount("c") }
        advanceUntilIdle()

        assertTrue(away.isCompleted && away.await(), "a switch away from A is not blocked by A's re-registration")
        assertEquals("c", active)
        register.complete(Unit)
        advanceUntilIdle()
        assertFalse("a" in registered, "the cancelled re-registration must not land after A's unregistration")
        coVerify(exactly = 2) { push.unregisterToken("a") }
    }
}
