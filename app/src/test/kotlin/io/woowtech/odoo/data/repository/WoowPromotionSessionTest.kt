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
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * pi 0930b Android P2 (live demo111 1001): on the WOOW brand, logging out B promoted A, whose WebView
 * then signed in again and left A's original — still valid — server session orphaned. Like Apporo
 * (5649944), the promoted account must get its still-valid session (proven uid AND db) published to
 * the jar instead of a new sign-in; an unproven one is forgotten and nothing is published.
 */
class WoowPromotionSessionTest {
    private val a = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11)
    private val b = OdooAccount("b", "https://fixture.test", "db-b", "user-b", "B", userId = 22)
    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var api: OdooJsonRpcClient
    private lateinit var push: FcmTokenRepository
    private val webSessions = mutableMapOf<String, String>()
    private var webOwner: String? = null
    private val accounts = linkedMapOf<String, OdooAccount>()
    private var active: String? = null
    private var jar: String? = null
    private val revoked = mutableListOf<String>()
    private val published = mutableListOf<String>()

    private val cleaner = object : AccountWebDataCleaner {
        override suspend fun webViewSessionIdOf(accountId: String, serverUrl: String): String? =
            webSessions[accountId]?.takeIf { webOwner == accountId }
        override suspend fun removeAccountData(accountId: String, serverUrl: String, removal: WebDataRemoval) = Unit
    }

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true)
        api = mockk(relaxed = true); push = mockk(relaxed = true)
        every { api.publishSession(any(), any()) } answers { jar = secondArg(); published += secondArg<String>() }
        every { api.getSessionId(any()) } answers { jar }
        every { api.clearCookies(any()) } answers { jar = null }
        coEvery { api.revokeSession(any(), any()) } answers { revoked += secondArg<String>(); true }
        // WOOW sign-in: the client itself stores THIS response's session in the native jar.
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
        accounts["a"] = a; accounts["b"] = b
    }

    private fun repo() =
        AccountRepository(dao, prefs, api, AppBrand.forCode("woowtech"), null, CoroutineScope(Dispatchers.Unconfined)).also {
            it.fcmTokenRepository = push
            it.webDataCleaner = cleaner
        }

    /** A signs in and is displayed (its WebView carries its session), then B is added on the same host. */
    private suspend fun aThenB(repo: AccountRepository) {
        repo.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        webOwner = "a"; webSessions["a"] = "a-sid-fixture"
        repo.authenticate(b.serverUrl, b.database, b.username, "password-fixture")
        webOwner = "b"; webSessions["b"] = "b-sid-fixture"
    }

    @Test
    fun `Given WOOW A then B on one host when B logs out and A's session is still valid then A gets it back without a new sign-in`() = runTest {
        val repo = repo()
        aThenB(repo)
        coEvery { api.sessionOwnership(a.serverUrl, "a-sid-fixture", 11, "db-a") } returns SessionOwnership.Belongs

        repo.logout("b")

        assertEquals("a", active)
        assertEquals("a-sid-fixture", jar, "the promoted A must be handed its own still-valid session")
        assertEquals(listOf("a-sid-fixture"), published)
        assertFalse("a-sid-fixture" in revoked)
        coVerify(exactly = 1) { api.authenticate(a.serverUrl, a.database, a.username, any()) } // only A's original sign-in
    }

    @Test
    fun `Given WOOW A's remembered session is no longer valid when B logs out then nothing of A is published`() = runTest {
        val repo = repo()
        aThenB(repo)
        coEvery { api.sessionOwnership(any(), any(), any(), any()) } returns SessionOwnership.ProvenMismatch

        repo.logout("b")

        assertEquals("a", active)
        verify(exactly = 0) { api.publishSession(any(), "a-sid-fixture") }
    }

    @Test
    fun `Given WOOW A then B when B logs out then the server proves A's remembered session before promotion`() = runTest {
        val repo = repo()
        aThenB(repo)
        coEvery { api.sessionOwnership(a.serverUrl, "a-sid-fixture", 11, "db-a") } returns SessionOwnership.Belongs

        repo.logout("b")

        coVerify { api.sessionOwnership(a.serverUrl, "a-sid-fixture", 11, "db-a") }
    }
}
