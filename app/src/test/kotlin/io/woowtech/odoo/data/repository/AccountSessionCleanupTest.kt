package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * iOS demo111 D1 parity (2026-09-30): logging out / removing an account wipes only THAT account's
 * WebView data and native session and revokes its sessions on the server — never a same-host sibling's.
 */
class AccountSessionCleanupTest {
    private val a = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11)
    private val b = OdooAccount("b", "https://fixture.test", "db-b", "user-b", "B", userId = 22)
    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var api: OdooJsonRpcClient
    private lateinit var push: FcmTokenRepository
    private lateinit var cleaner: FakeCleaner
    private val accounts = linkedMapOf<String, OdooAccount>()
    private var active: String? = null
    private var jar: String? = null
    private val revoked = mutableListOf<String>()

    private class FakeCleaner : AccountWebDataCleaner {
        val webSessions = mutableMapOf<String, String>()
        val removals = mutableListOf<Triple<String, String, WebDataRemoval>>()
        override suspend fun webViewSessionIdOf(accountId: String, serverUrl: String) = webSessions[accountId]
        override suspend fun removeAccountData(accountId: String, serverUrl: String, removal: WebDataRemoval) {
            removals += Triple(accountId, serverUrl, removal)
        }
    }

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        cleaner = FakeCleaner()
        accounts.clear(); active = null; jar = null; revoked.clear()
        every { api.publishApporoSession(any(), any()) } answers { jar = secondArg() }
        every { api.getSessionId(any()) } answers { jar }
        every { api.clearCookies(any()) } answers { jar = null }
        coEvery { api.revokeSession(any(), any()) } answers { revoked += secondArg<String>(); true }
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
        coEvery { api.authenticateApporoIsolated(any(), any(), any(), any()) } answers {
            val account = accounts.values.first { it.username == thirdArg<String>() }
            AuthResult.Success(account.userId!!, "${account.id}-sid-fixture", account.username, account.displayName)
        }
    }

    private fun repo(brand: String = "apporo") =
        AccountRepository(dao, prefs, api, AppBrand.forCode(brand), null, CoroutineScope(Dispatchers.Unconfined)).also {
            it.fcmTokenRepository = push
            it.webDataCleaner = cleaner
        }

    private suspend fun signInBoth(repo: AccountRepository, last: OdooAccount) {
        accounts["a"] = a; accounts["b"] = b
        val first = if (last == a) b else a
        repo.authenticate(first.serverUrl, first.database, first.username, "password-fixture")
        repo.authenticate(last.serverUrl, last.database, last.username, "password-fixture")
    }

    @Test
    fun `Given active B logs out while A shares the host then only B's data is wiped and B's sessions revoked`() = runTest {
        val repo = repo()
        signInBoth(repo, last = b)
        assertEquals("b", active)
        cleaner.webSessions["b"] = "b-web-sid"

        repo.logout("b")

        assertEquals(listOf(Triple("b", b.serverUrl, WebDataRemoval(cookies = true, originStorage = false, everything = false))), cleaner.removals)
        assertEquals(setOf("b-sid-fixture", "b-web-sid"), revoked.toSet())
        verify(exactly = 1) { api.clearCookies("fixture.test") } // the jar held B's session
        coVerifyOrder {
            push.unregisterToken("b")
            api.revokeSession(b.serverUrl, any())
        }
        coVerifyOrder {
            api.clearCookies("fixture.test")
            dao.deleteAccountById("b")
        }
        assertEquals("a", active)
    }

    @Test
    fun `Given non-active B removed while A is displayed then cookies and A's jar session are kept`() = runTest {
        val repo = repo()
        signInBoth(repo, last = a)
        assertEquals("a", active)
        cleaner.webSessions["a"] = "a-web-sid" // the WebView cookies are A's, not B's

        repo.removeAccount("b")

        assertEquals(listOf(Triple("b", b.serverUrl, WebDataRemoval(cookies = false, originStorage = false, everything = false))), cleaner.removals)
        assertEquals(listOf("b-sid-fixture"), revoked)
        verify(exactly = 0) { api.clearCookies(any()) }
        assertEquals("a-sid-fixture", jar)
    }

    @Test
    fun `Given the last account logs out then all WebView data is wiped and its sessions revoked`() = runTest {
        val repo = repo()
        accounts["a"] = a
        repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        cleaner.webSessions["a"] = "a-web-sid"

        repo.logout("a")

        assertEquals(listOf(Triple("a", a.serverUrl, WebDataRemoval(cookies = true, originStorage = true, everything = true))), cleaner.removals)
        assertEquals(setOf("a-sid-fixture", "a-web-sid"), revoked.toSet())
        verify { api.clearCookies("fixture.test") }
    }

    @Test
    fun `Given an unproven jar session and a same-host sibling then jar is cleared but that session is never revoked`() = runTest {
        // Process restart: nothing known in memory; the jar holds a session of unknown owner.
        accounts["a"] = a; accounts["b"] = b; active = "b"; jar = "unknown-owner-sid"
        cleaner.webSessions["b"] = "b-web-sid"

        repo().logout("b")

        verify { api.clearCookies("fixture.test") }
        assertEquals(listOf("b-web-sid"), revoked)
    }

    @Test
    fun `Given WOOW brand logout then the WebView data is wiped and its WebView session revoked too`() = runTest {
        accounts["a"] = a; active = "a"
        cleaner.webSessions["a"] = "a-web-sid"

        repo("woowtech").logout("a")

        assertEquals(1, cleaner.removals.size)
        assertEquals(listOf("a-web-sid"), revoked)
        coVerify { dao.deleteAccountById("a") }
    }
}
