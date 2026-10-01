package io.woowtech.odoo.data.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * pi 1001d P1: the shared self-heal published the new session to the host jar inside the sign-in call,
 * BEFORE the repository could check that it was still current. The heal must sign in without touching the
 * jar and leave publication to the fence; a refused heal publishes nothing.
 */
class SelfHealFenceTest {
    private val account = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11)
    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var api: OdooJsonRpcClient
    private lateinit var reauth: SessionReauthenticator

    @BeforeEach
    fun setup() {
        dao = mockk(relaxed = true); prefs = mockk(relaxed = true); api = mockk(relaxed = true)
        coEvery { dao.getAllAccountsList() } returns listOf(account)
        every { prefs.getPassword("a") } returns "password-fixture"
        coEvery { api.authenticateForSelfHeal(any(), any(), any(), any()) } returns
            AuthResult.Success(11, "a-heal-sid", "user-a", "A")
        reauth = SessionReauthenticator(dao, prefs, api, mockk(relaxed = true))
    }

    private fun committer(admit: Boolean) = object : SessionReauthenticator.HealCommitter {
        val calls = mutableListOf<String>()
        override fun beginHeal(accountId: String): Long { calls += "begin"; return 7L }
        override suspend fun commitHeal(accountId: String, serverUrl: String, sessionId: String, ticket: Long): Boolean {
            calls += "commit:$ticket"; return admit
        }
    }

    @Test
    fun `Given the fence refuses the heal then nothing reaches the jar and the request is not retried`() {
        val fence = committer(admit = false)
        reauth.healCommitter = fence

        assertFalse(reauth.reauthenticateForHost("fixture.test"))

        verify(exactly = 0) { api.publishSession(any(), any()) }
        verify(exactly = 0) { api.publishApporoSession(any(), any()) }
        coVerify(exactly = 0) { api.authenticate(any(), any(), any(), any()) }
        assertTrue(fence.calls == listOf("begin", "commit:7"), "ticket taken before sign-in: ${fence.calls}")
    }

    @Test
    fun `Given the fence admits the heal then only the fence publishes and the request is retried`() {
        reauth.healCommitter = committer(admit = true)

        assertTrue(reauth.reauthenticateForHost("fixture.test"))

        verify(exactly = 0) { api.publishSession(any(), any()) } // the committer publishes, not the engine
    }

    @Test
    fun `Given no fence is wired then the healed session is published directly`() {
        assertTrue(reauth.reauthenticateForHost("fixture.test"))

        verify(exactly = 1) { api.publishSession(account.fullServerUrl, "a-heal-sid") }
    }
}
