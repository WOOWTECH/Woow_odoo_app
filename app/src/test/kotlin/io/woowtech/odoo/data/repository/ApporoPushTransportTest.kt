package io.woowtech.odoo.data.repository

import com.google.gson.JsonParser
import io.mockk.*
import io.woowtech.odoo.testutil.onlyLoopback
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Phase 3: actual loopback HTTP fixtures, account/session isolation and bounded cap/write healing.
 * HTTPS URLs are rewritten ONLY by this test interceptor to loopback (not a TLS integration test).
 */
class ApporoPushTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var dao: AccountDao
    private lateinit var prefs: EncryptedPrefs
    private lateinit var signal: ReloginSignal
    private lateinit var transport: ApporoPushTransport
    private lateinit var repo: FcmTokenRepositoryImpl
    private lateinit var client: OkHttpClient
    private val accounts = linkedMapOf<String, OdooAccount>()
    private var savedToken: String? = "token-fixture"
    private var onRequest: ((Request) -> Unit)? = null
    private val a get() = OdooAccount("a", "https://fixture.test:8443", "db-a", "user-a", "A", userId = 11)
    private val b get() = OdooAccount("b", "https://fixture.test:8443", "db-b", "user-b", "B", userId = 22)

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        dao = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        signal = mockk(relaxed = true)
        accounts.clear()
        accounts[a.id] = a
        savedToken = "token-fixture"
        onRequest = null
        coEvery { dao.getAccountById(any()) } answers { accounts[firstArg()] }
        coEvery { dao.getAllAccountsList() } answers { accounts.values.toList() }
        every { prefs.getPassword(any()) } answers { if (accounts.containsKey(firstArg<String>())) "password-fixture" else null }
        every { prefs.getFcmToken() } answers { savedToken }
        every { prefs.saveFcmToken(any()) } answers { savedToken = firstArg() }
        client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = error("Must not pollute shared jar")
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder()
                    .name("session_id").value("wrong-account-fixture").domain(url.host).build())
            })
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals("fixture.test", request.url.host)
                onRequest?.invoke(request)
                // Every network connection is confined to this freshly-created loopback server.
                chain.proceed(request.newBuilder().url(request.url.newBuilder()
                    .scheme("http").host("127.0.0.1").port(server.port).build()).build())
            }.onlyLoopback(server).build()
        transport = ApporoPushTransport(dao, prefs, signal, client)
        repo = FcmTokenRepositoryImpl(prefs, dao, client, AppBrand.forCode("apporo"), transport)
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun json(body: String) = server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    private fun auth(uid: Int = 11, sid: String = "a-fixture") = server.enqueue(MockResponse()
        .setBody("""{"result":{"uid":$uid}}""").addHeader("Set-Cookie", "session_id=$sid; Path=/; Secure; HttpOnly"))
    private fun cap() = json("""{"result":{"push_contract_version":2,"supported_brands":["woowtech","apporo"]}}""")
    private fun ack(brand: String = "apporo") = json("""{"result":{"device_id":1,"odoo_tenant_id":"tenant-fixture","app_brand":"$brand","push_contract_version":2}}""")
    private fun removed() = json("""{"result":{"success":true}}""")
    private fun take(): RecordedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
    private fun params(request: RecordedRequest) = JsonParser.parseString(request.body.readUtf8()).asJsonObject.getAsJsonObject("params")
    private fun paths(count: Int) = (1..count).map { take().path }
    private val authPath = "/web/session/authenticate"
    private val capPath = "/woow_fcm_push/capabilities"
    private val registerPath = "/woow_fcm_push/register"
    private val unregisterPath = "/woow_fcm_push/unregister"

    @Test
    fun `Given Apporo when register and unregister then every write has fresh cap and fixed brand`() = runTest {
        auth(); cap(); ack(); cap(); removed()
        assertTrue(repo.registerToken("a", "token-fixture").isSuccess)
        assertEquals(PushRegistrationStatus.ACKNOWLEDGED, repo.registrationStatuses.value["a"])
        assertTrue(repo.unregisterToken("a").isSuccess)
        val requests = (1..5).map { take() }
        assertEquals(listOf(authPath, capPath, registerPath, capPath, unregisterPath), requests.map { it.path })
        assertNull(requests[0].getHeader("Cookie"))
        requests.drop(1).forEach { assertEquals("session_id=a-fixture", it.getHeader("Cookie")) }
        assertEquals(0, params(requests[1]).size())
        listOf(requests[2], requests[4]).forEach { assertEquals("apporo", params(it)["app_brand"].asString) }
        coVerify { dao.updateTenantId("a", "tenant-fixture") }
        assertEquals(PushRegistrationStatus.UNREGISTERED, repo.registrationStatuses.value["a"])
    }

    @Test
    fun `Given unsupported cap for register and unregister then zero writes and no fallback`() = runTest {
        auth(); json("""{"result":{"push_contract_version":1,"supported_brands":["woowtech"]}}""")
        json("""{"result":{"push_contract_version":2,"supported_brands":[]}}""")
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertTrue(repo.unregisterToken("a").isFailure)
        assertEquals(listOf(authPath, capPath, capPath), paths(3))
        assertEquals(3, server.requestCount)
        assertEquals(PushRegistrationStatus.NOT_CONFIGURED, repo.registrationStatuses.value["a"])
    }

    @Test
    fun `Given missing capability route when register then no write`() = runTest {
        auth(); server.enqueue(MockResponse().setResponseCode(404).setBody("fixture absent"))
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(listOf(authPath, capPath), paths(2))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Given wrong brand ACK then fail without fallback or tenant persistence`() = runTest {
        auth(); cap(); ack("woowtech")
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(3, server.requestCount)
        coVerify(exactly = 0) { dao.updateTenantId(any(), any()) }
        assertEquals(PushRegistrationStatus.CONTRACT_REJECTED, repo.registrationStatuses.value["a"])
    }

    @Test
    fun `Given write expires when healed then new session must pass cap before write retry`() = runTest {
        auth(); cap(); server.enqueue(MockResponse().setResponseCode(401))
        auth(sid = "a-healed-fixture"); cap(); ack()
        assertTrue(repo.registerToken("a", "token-fixture").isSuccess)
        val requests = (1..6).map { take() }
        assertEquals(listOf(authPath, capPath, registerPath, authPath, capPath, registerPath), requests.map { it.path })
        assertEquals("session_id=a-fixture", requests[2].getHeader("Cookie"))
        requests.takeLast(2).forEach { assertEquals("session_id=a-healed-fixture", it.getHeader("Cookie")) }
    }

    @Test
    fun `Given cap session expired envelope when healed then recheck precedes first write`() = runTest {
        auth(); json("""{"error":{"code":100,"data":{"name":"odoo.http.SessionExpiredException"}}}""")
        auth(sid = "healed-fixture"); cap(); ack()
        assertTrue(repo.registerToken("a", "token-fixture").isSuccess)
        assertEquals(listOf(authPath, capPath, authPath, capPath, registerPath), paths(5))
    }

    @Test
    fun `Given second expiry after healing then stop without third auth or write`() = runTest {
        auth(); server.enqueue(MockResponse().setResponseCode(401))
        auth(); cap(); server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(listOf(authPath, capPath, authPath, capPath, registerPath), paths(5))
        assertEquals(5, server.requestCount)
        assertEquals(PushRegistrationStatus.SIGN_IN_REQUIRED, repo.registrationStatuses.value["a"])
    }

    @Test
    fun `Given new session lacks cap after write expiry then do not retry write`() = runTest {
        auth(); cap(); server.enqueue(MockResponse().setResponseCode(401))
        auth(); json("""{"result":{"push_contract_version":2,"supported_brands":[]}}""")
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(listOf(authPath, capPath, registerPath, authPath, capPath), paths(5))
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `Given two same-origin accounts with different DB when fanout then cookies caps and statuses stay isolated`() = runTest {
        accounts["b"] = b
        auth(); cap(); ack()
        auth(22, "b-fixture"); json("""{"result":{"push_contract_version":2,"supported_brands":["woowtech"]}}""")
        assertTrue(repo.registerTokenForAllAccounts("token-fixture").isFailure)
        val requests = (1..5).map { take() }
        assertEquals("db-a", params(requests[0])["db"].asString)
        assertEquals("db-b", params(requests[3])["db"].asString)
        assertNull(requests[3].getHeader("Cookie"))
        assertEquals("session_id=b-fixture", requests[4].getHeader("Cookie"))
        assertEquals(PushRegistrationStatus.ACKNOWLEDGED, repo.registrationStatuses.value["a"])
        assertEquals(PushRegistrationStatus.NOT_CONFIGURED, repo.registrationStatuses.value["b"])
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `Given two successful accounts when A session heals then B keeps its own SID`() = runTest {
        accounts["b"] = b
        auth(); cap(); ack(); auth(22, "b-fixture"); cap(); ack()
        assertTrue(repo.registerTokenForAllAccounts("token-fixture").isSuccess)
        cap(); server.enqueue(MockResponse().setResponseCode(401)); auth(sid = "a-new-fixture"); cap(); ack()
        cap(); ack()
        assertTrue(repo.registerToken("a", "token-fixture").isSuccess)
        assertTrue(repo.registerToken("b", "token-fixture").isSuccess)
        val requests = (1..13).map { take() }
        assertEquals("session_id=a-new-fixture", requests[10].getHeader("Cookie"))
        assertEquals("session_id=b-fixture", requests[11].getHeader("Cookie"))
        assertEquals("session_id=b-fixture", requests[12].getHeader("Cookie"))
    }

    @Test
    fun `Given token rotation then old unregister and new register each require cap`() = runTest {
        auth(); cap(); removed(); cap(); ack()
        assertTrue(repo.registerTokenForAllAccounts("rotated-fixture").isSuccess)
        val requests = (1..5).map { take() }
        assertEquals(listOf(authPath, capPath, unregisterPath, capPath, registerPath), requests.map { it.path })
        assertEquals("token-fixture", params(requests[2])["fcm_token"].asString)
        assertEquals("rotated-fixture", params(requests[4])["fcm_token"].asString)
    }

    @Test
    fun `Given zero accounts when token arrives then replay after account available`() = runTest {
        accounts.clear()
        assertTrue(repo.registerTokenForAllAccounts("early-fixture").isSuccess)
        assertEquals(0, server.requestCount)
        assertEquals("early-fixture", savedToken)
        accounts["a"] = a
        auth(); cap(); ack()
        assertTrue(repo.reconcileOnAccountAvailable().isSuccess)
        assertEquals(3, server.requestCount)
        take(); take()
        assertEquals("early-fixture", params(take())["fcm_token"].asString)
    }

    @Test
    fun `Given repeated launch reconcile then cap is not cached`() = runTest {
        auth(); cap(); ack(); cap(); ack()
        assertTrue(repo.reconcileToken("token-fixture").isSuccess)
        assertTrue(repo.reconcileToken("token-fixture").isSuccess)
        assertEquals(listOf(authPath, capPath, registerPath, capPath, registerPath), paths(5))
    }

    @Test
    fun `Given account removed during cap then registration write is stopped`() = runTest {
        auth(); cap()
        onRequest = { if (it.url.encodedPath == capPath) accounts.remove("a") }
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(listOf(authPath, capPath), paths(2))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Given explicit captured cleanup when removed during cap then unregister still runs`() = runTest {
        auth(); cap(); removed()
        onRequest = { if (it.url.encodedPath == capPath) accounts.remove("a") }
        assertTrue(repo.unregisterToken("a").isSuccess)
        assertEquals(listOf(authPath, capPath, unregisterPath), paths(3))
    }

    @Test
    fun `Given removed account cleanup SID expires then never reauthenticate or borrow another account`() = runTest {
        accounts["b"] = b
        auth(); server.enqueue(MockResponse().setResponseCode(401))
        onRequest = { if (it.url.encodedPath == capPath) accounts.remove("a") }
        assertTrue(repo.unregisterToken("a").isFailure)
        assertEquals(listOf(authPath, capPath), paths(2))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Given identity port changes during cap then register and cleanup both stop`() = runTest {
        auth(); cap()
        onRequest = { if (it.url.encodedPath == capPath) accounts["a"] = a.copy(serverUrl = "https://fixture.test:9443") }
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Given invalid credentials circuits when manual login A succeeds then only A resets`() = runTest {
        accounts["b"] = b
        json("""{"result":{"uid":false}}"""); json("""{"result":{"uid":false}}""")
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertTrue(repo.registerToken("b", "token-fixture").isFailure)
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(2, server.requestCount)
        repo.onManualLogin("a", "manual-a-fixture")
        cap(); ack()
        assertTrue(repo.registerToken("a", "token-fixture").isSuccess)
        assertTrue(repo.registerToken("b", "token-fixture").isFailure)
        assertEquals(4, server.requestCount)
        verify { signal.request("a", ReloginReason.INVALID_CREDENTIALS) }
        verify { signal.request("b", ReloginReason.INVALID_CREDENTIALS) }
    }

    @Test
    fun `Given non HTTPS stored origin then no credentials or push leave client`() = runTest {
        accounts["a"] = a.copy(serverUrl = "http://fixture.test")
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Given legacy WOOW when register and unregister then omit brand and capability even with v2 ACK`() = runTest {
        val legacyClient = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        val legacy = FcmTokenRepositoryImpl(prefs, dao, legacyClient, AppBrand.forCode("woowtech"))
        ack("woowtech"); removed()
        assertTrue(legacy.registerToken("a", "token-fixture").isSuccess)
        assertTrue(legacy.unregisterToken("a").isSuccess)
        val requests = listOf(take(), take())
        assertEquals(listOf(registerPath, unregisterPath), requests.map { it.path })
        requests.forEach { assertFalse(params(it).has("app_brand")) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Given logout cleanup failed when local account forgotten then stale ACK cannot survive`() = runTest {
        auth(); cap(); ack()
        assertTrue(repo.registerToken("a", "token-fixture").isSuccess)
        json("""{"result":{"push_contract_version":2,"supported_brands":[]}}""")
        assertTrue(repo.unregisterToken("a").isFailure)
        repo.forgetAccount("a")
        assertFalse(repo.registrationStatuses.value.containsKey("a"))
    }

    private fun accountRepository(): AccountRepository {
        val odoo = mockk<io.woowtech.odoo.data.api.OdooJsonRpcClient>(relaxed = true)
        coEvery { odoo.authenticateApporoIsolated(any(), any(), any(), any()) } returns
            io.woowtech.odoo.domain.model.AuthResult.Success(11, "login-fixture", "user-a", "A")
        coEvery { dao.findAccount(a.serverUrl, a.database, a.username) } returns a
        coEvery { dao.insertAccount(any()) } answers {
            val account = firstArg<OdooAccount>()
            accounts[account.id] = account
        }
        coEvery { dao.deleteAccountById(any()) } answers { accounts.remove(firstArg<String>()); Unit }
        return AccountRepository(dao, prefs, odoo, AppBrand.forCode("apporo")).also { it.fcmTokenRepository = repo }
    }

    @Test
    fun `Given login succeeds with push unconfigured then login remains successful and status explains failure`() = runTest {
        val accountRepo = accountRepository()
        json("""{"result":{"push_contract_version":2,"supported_brands":[]}}""")
        val result = accountRepo.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        assertTrue(result is io.woowtech.odoo.domain.model.AuthResult.Success)
        assertEquals(PushRegistrationStatus.NOT_CONFIGURED, repo.registrationStatuses.value["a"])
        assertEquals(listOf(capPath), paths(1))
        coVerify(exactly = 1) { dao.insertAccount(any()) }
    }

    @Test
    fun `Given successful login then logout awaits branded cleanup before deleting local account`() = runTest {
        val accountRepo = accountRepository()
        cap(); ack(); cap(); removed()
        assertTrue(accountRepo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") is
            io.woowtech.odoo.domain.model.AuthResult.Success)
        onRequest = { if (it.url.encodedPath == unregisterPath) assertTrue(accounts.containsKey("a")) }
        assertFalse(accountRepo.logout("a"))
        assertFalse(accounts.containsKey("a"))
        assertFalse(repo.registrationStatuses.value.containsKey("a"))
        assertEquals(listOf(capPath, registerPath, capPath, unregisterPath), paths(4))
        verify { prefs.removePassword("a") }
    }

    @Test
    fun `Given remove push capability rejected then local removal still succeeds without remote write`() = runTest {
        val accountRepo = accountRepository()
        auth(); json("""{"result":{"push_contract_version":1,"supported_brands":["woowtech"]}}""")
        accountRepo.removeAccount("a")
        assertFalse(accounts.containsKey("a"))
        assertEquals(listOf(authPath, capPath), paths(2))
        assertFalse(repo.registrationStatuses.value.containsKey("a"))
        verify { prefs.removePassword("a") }
    }

    @Test
    fun `Given logout transport unavailable then local logout still completes`() = runTest {
        val accountRepo = accountRepository()
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(accountRepo.logout("a"))
        assertFalse(accounts.containsKey("a"))
        verify { prefs.removePassword("a") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Given temporary authentication errors then retry until circuit threshold without invalid-credential signal`() = runTest {
        repeat(3) { json("""{"error":{"data":{"name":"odoo.exceptions.UserError"},"message":"fixture internal details"}}""") }
        repeat(3) { assertTrue(repo.registerToken("a", "token-fixture").isFailure) }
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(3, server.requestCount)
        verify(exactly = 0) { signal.request("a", ReloginReason.INVALID_CREDENTIALS) }
        verify(exactly = 1) { signal.request("a", ReloginReason.REAUTH_CIRCUIT_OPEN) }
    }

    @Test
    fun `Given cleanup account identity changes during capability then do not unregister replacement`() = runTest {
        auth(); cap()
        onRequest = { if (it.url.encodedPath == capPath) accounts["a"] = a.copy(database = "other-db") }
        assertTrue(repo.unregisterToken("a").isFailure)
        assertEquals(listOf(authPath, capPath), paths(2))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Given server redirects authentication then do not follow or leak credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://outside.invalid/auth"))
        assertTrue(repo.registerToken("a", "token-fixture").isFailure)
        assertEquals(1, server.requestCount)
    }


    @Test
    fun `Given same host and database but different port and base path then sessions stay account bound`() = runTest {
        accounts["b"] = b.copy(serverUrl = "https://fixture.test:9443/odoo", database = a.database)
        auth(); cap(); ack(); auth(22, "b-path-fixture"); cap(); ack()
        assertTrue(repo.registerTokenForAllAccounts("token-fixture").isSuccess)
        val requests = (1..6).map { take() }
        assertEquals(listOf(authPath, capPath, registerPath,
            "/odoo$authPath", "/odoo$capPath", "/odoo$registerPath"), requests.map { it.path })
        assertEquals("session_id=a-fixture", requests[2].getHeader("Cookie"))
        assertEquals("session_id=b-path-fixture", requests[5].getHeader("Cookie"))
    }

    @Test
    fun `Given real manual auth valid SID but unsupported capability then one auth and login remains successful`() = runTest {
        val api = io.woowtech.odoo.data.api.OdooJsonRpcClient(client, AppBrand.forCode("apporo"), client)
        coEvery { dao.findAccount(a.serverUrl, a.database, a.username) } returns a
        coEvery { dao.insertAccount(any()) } answers { val account = firstArg<OdooAccount>(); accounts[account.id] = account }
        val accountRepo = AccountRepository(dao, prefs, api, AppBrand.forCode("apporo")).also { it.fcmTokenRepository = repo }
        auth(sid = "manual-real-fixture"); json("""{"result":{"push_contract_version":2,"supported_brands":[]}}""")
        assertTrue(accountRepo.authenticate(a.serverUrl, a.database, a.username, "password-fixture") is
            io.woowtech.odoo.domain.model.AuthResult.Success)
        val requests = listOf(take(), take())
        assertEquals(listOf(authPath, capPath), requests.map { it.path })
        assertEquals("session_id=manual-real-fixture", requests[1].getHeader("Cookie"))
        assertEquals("manual-real-fixture", api.getSessionId("fixture.test"))
        assertEquals(PushRegistrationStatus.NOT_CONFIGURED, repo.registrationStatuses.value["a"])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Given real manual auth lacks SID then preserve existing B cookie with zero local mutations`() = runTest {
        val api = io.woowtech.odoo.data.api.OdooJsonRpcClient(client, AppBrand.forCode("apporo"), client)
        api.publishApporoSession(b.serverUrl, "b-existing-fixture")
        val accountRepo = AccountRepository(dao, prefs, api, AppBrand.forCode("apporo")).also { it.fcmTokenRepository = repo }
        json("""{"result":{"uid":11}}""")
        val result = accountRepo.authenticate(a.serverUrl, a.database, a.username, "password-fixture")
        assertEquals(io.woowtech.odoo.domain.model.AuthResult.ErrorType.SESSION_EXPIRED,
            (result as io.woowtech.odoo.domain.model.AuthResult.Error).type)
        assertEquals("b-existing-fixture", api.getSessionId("fixture.test"))
        coVerify(exactly = 0) { dao.insertAccount(any()) }
        coVerify(exactly = 0) { dao.deactivateAllAccounts() }
        coVerify(exactly = 0) { dao.activateAccount(any()) }
        verify(exactly = 0) { prefs.savePassword(any(), any()) }
        assertEquals(listOf(authPath), paths(1))
        assertEquals(1, server.requestCount)
    }

}
