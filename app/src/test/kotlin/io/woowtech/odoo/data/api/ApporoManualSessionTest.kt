package io.woowtech.odoo.data.api

import io.mockk.*
import io.woowtech.odoo.testutil.onlyLoopback
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.ReloginSignal
import io.woowtech.odoo.domain.model.OdooAccount
import io.woowtech.odoo.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Phase 3: Apporo manual auth takes its own response SID, never a host-jar lookup. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApporoManualSessionTest {
    private fun fixture(block: suspend (OdooJsonRpcClient, MockWebServer) -> Unit) = runTest {
        val server = MockWebServer().also { it.start() }
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = error("Shared jar must not be written")
                override fun loadForRequest(url: HttpUrl): List<Cookie> = error("Shared jar must not be read")
            }).addInterceptor { chain ->
                assertEquals("fixture.test", chain.request().url.host)
                chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder()
                    .scheme("http").host("127.0.0.1").port(server.port).build()).build())
            }.onlyLoopback(server).build()
        try { block(OdooJsonRpcClient(client, AppBrand.forCode("apporo"), client), server) }
        finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `Given existing B UI cookie when A authenticates then return own SID and leave UI jar alone`() = fixture { api, server ->
        api.publishApporoSession("https://fixture.test:8443", "b-fixture")
        server.enqueue(MockResponse().setBody("""{"result":{"uid":11,"name":"A"}}""")
            .addHeader("Set-Cookie", "session_id=a-fixture; Path=/; Secure; HttpOnly"))
        val result = api.authenticateApporoIsolated("https://fixture.test:8443", "db-a", "user-a", "password-fixture") as AuthResult.Success
        assertEquals("a-fixture", result.sessionId)
        assertEquals("b-fixture", api.getSessionId("fixture.test"))
        assertNull(server.takeRequest().getHeader("Cookie"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Given no Set-Cookie when login succeeds then never borrow existing B SID`() = fixture { api, server ->
        api.publishApporoSession("https://fixture.test", "b-fixture")
        server.enqueue(MockResponse().setBody("""{"result":{"uid":11}}"""))
        val result = api.authenticateApporoIsolated("https://fixture.test", "db-a", "user-a", "password-fixture") as AuthResult.Error
        assertEquals(AuthResult.ErrorType.SESSION_EXPIRED, result.type)
        assertEquals("b-fixture", api.getSessionId("fixture.test"))
    }

    @Test
    fun `Given foreign-domain cookie then login SID is unproven and not borrowed`() = fixture { api, server ->
        server.enqueue(MockResponse().setBody("""{"result":{"uid":11}}""")
            .addHeader("Set-Cookie", "session_id=foreign-fixture; Domain=elsewhere.invalid; Secure"))
        val result = api.authenticateApporoIsolated("https://fixture.test", "db-a", "user-a", "password-fixture") as AuthResult.Error
        assertEquals(AuthResult.ErrorType.SESSION_EXPIRED, result.type)
    }

    @Test
    fun `Given auth redirect or invalid origin then no credential forwarding`() = fixture { api, server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://outside.invalid"))
        assertTrue(api.authenticateApporoIsolated("https://fixture.test", "db", "user", "password-fixture") is AuthResult.Error)
        assertTrue(api.authenticateApporoIsolated("http://fixture.test", "db", "user", "password-fixture") is AuthResult.Error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Given empty or expired SID then fail session establishment without modifying B`() = fixture { api, server ->
        api.publishApporoSession("https://fixture.test", "b-fixture")
        listOf("session_id=; Path=/; Secure", "session_id=expired-fixture; Path=/; Secure; Max-Age=0").forEach { cookie ->
            server.enqueue(MockResponse().setBody("""{"result":{"uid":11}}""").addHeader("Set-Cookie", cookie))
            val result = api.authenticateApporoIsolated("https://fixture.test", "db-a", "user-a", "password-fixture") as AuthResult.Error
            assertEquals(AuthResult.ErrorType.SESSION_EXPIRED, result.type)
            assertEquals("b-fixture", api.getSessionId("fixture.test"))
        }
        assertEquals(2, server.requestCount)
    }


    @Test
    fun `Given expired WebView SID when MainViewModel heals through shared reauth then WebView source has new SID`() = fixture { api, server ->
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val account = OdooAccount("a", "https://fixture.test", "db-a", "user-a", "A", userId = 11, isActive = true)
            val dao = mockk<AccountDao>(relaxed = true)
            val prefs = mockk<EncryptedPrefs>(relaxed = true)
            coEvery { dao.getAllAccountsList() } returns listOf(account)
            coEvery { dao.getActiveAccountOnce() } returns account
            every { prefs.getPassword("a") } returns "password-fixture"
            val repo = AccountRepository(dao, prefs, api, AppBrand.forCode("apporo"))
            val signal = ReloginSignal()
            val reauth = SessionReauthenticator(dao, prefs, api, signal)
            val viewModel = MainViewModel(repo, prefs, mockk(relaxed = true), signal, mockk(relaxed = true), reauth, mockk(relaxed = true))
            api.publishApporoSession(account.serverUrl, "expired-fixture")
            assertEquals("expired-fixture", viewModel.getSessionId(account.serverUrl))
            server.enqueue(MockResponse().setBody("""{"result":{"uid":11,"name":"A"}}""")
                .addHeader("Set-Cookie", "session_id=new-fixture; Path=/; Secure; HttpOnly"))
            assertTrue(viewModel.selfHealActiveAccount("fixture.test"))
            // MainScreen uses this same getter before CookieManager.setCookie and reload.
            assertEquals("new-fixture", viewModel.getSessionId(account.serverUrl))
            assertEquals("new-fixture", repo.getSessionCookies(account.serverUrl).single().value)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"result":{"uid":11}}"""))
            assertFalse(viewModel.selfHealActiveAccount("fixture.test"))
            assertEquals("new-fixture", viewModel.getSessionId(account.serverUrl))
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `Given syntactically invalid origin then shared and isolated auth refuse before network without publishing`() = fixture { api, server ->
        api.publishApporoSession("https://fixture.test", "b-fixture")
        val shared = api.authenticate("https://bad host/", "db", "user", "password-fixture") as AuthResult.Error
        val isolated = api.authenticateApporoIsolated("https://bad host/", "db", "user", "password-fixture") as AuthResult.Error
        listOf(shared, isolated).forEach {
            assertEquals(AuthResult.ErrorType.INVALID_URL, it.type)
            assertEquals("Invalid server URL", it.message)
        }
        assertEquals(0, server.requestCount)
        assertEquals("b-fixture", api.getSessionId("fixture.test"))
    }
}
