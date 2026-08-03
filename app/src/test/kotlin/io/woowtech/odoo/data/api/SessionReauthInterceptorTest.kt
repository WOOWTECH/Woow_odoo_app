package io.woowtech.odoo.data.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.ReloginReason
import io.woowtech.odoo.data.repository.ReloginSignal
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SessionReauthInterceptor] + [SessionReauthenticator] — WI-3 (self-heal an expired
 * Odoo session during FCM register/unregister).
 *
 * ## Real Odoo contract
 * `/woow_fcm_push/register` is a `type='json', auth='user'` route, so Odoo signals an expired session
 * as **HTTP 200 with a JSON-RPC `SessionExpiredException` error envelope**, NOT HTTP 401. These tests
 * therefore drive the interceptor against a [MockWebServer] that returns that 200-error-body first,
 * then a 200 success — the shape a real server produces. (The earlier suite used a literal 401, which
 * an OkHttp `Authenticator` needs but which Odoo never actually returns for these routes.)
 *
 * The full 200-error-body -> re-auth -> retry pipeline runs through a real [OkHttpClient] wired with
 * the interceptor. The [OdooJsonRpcClient] re-auth call is mocked to return scripted [AuthResult]s.
 * Host/scheme-safety, single-flight and circuit-breaker guardrails are driven directly against
 * [SessionReauthenticator.reauthenticateForHost] (they are transport-agnostic).
 */
class SessionReauthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var reloginSignal: ReloginSignal
    private lateinit var reauthenticator: SessionReauthenticator
    private lateinit var interceptor: SessionReauthInterceptor
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        accountDao = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)
        reloginSignal = mockk(relaxed = true)
        reauthenticator = SessionReauthenticator(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
            reloginSignal = reloginSignal,
        )
        interceptor = SessionReauthInterceptor(reauthenticator)
        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Builds an account whose host matches the running MockWebServer. `serverUrl` starts with https
     * so the account passes the exact-host resolution even though the actual request goes to the
     * mock server's http host (resolution keys off the account's host, not its scheme, for the
     * live-client tests below).
     */
    private fun accountForMockServer(id: String = "acc1"): OdooAccount {
        val host = server.hostName + ":" + server.port
        return OdooAccount(
            id = id,
            serverUrl = "https://$host",
            database = "db",
            username = "admin",
            displayName = "Admin",
        )
    }

    private fun httpsAccount(id: String, host: String) = OdooAccount(
        id = id,
        serverUrl = "https://$host",
        database = "db",
        username = "admin",
        displayName = "Admin",
    )

    private fun executeRegister(): okhttp3.Response {
        val url = server.url("/woow_fcm_push/register")
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return client.newCall(request).execute()
    }

    /** The exact HTTP-200 JSON-RPC session-expired envelope Odoo returns for `type='json'` routes. */
    private fun sessionExpiredEnvelope(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody(
            """
            {"jsonrpc":"2.0","id":1,"error":{"code":100,"message":"Odoo Session Expired",
            "data":{"name":"odoo.http.SessionExpiredException","message":"Session expired",
            "debug":"","arguments":[],"exception_type":"session_expired"}}}
            """.trimIndent(),
        )

    // AC3.1 / AC3.2 — 200 session-expired body -> re-auth once -> retry succeeds

    @Test
    fun `Given 200 session-expired body then 200 when request runs then re-auths once and retry succeeds`() = runTest {
        val account = accountForMockServer()
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        every { encryptedPrefs.getPassword(account.id) } returns "stored-pass"
        coEvery {
            odooClient.authenticate(any(), any(), any(), any(), any())
        } returns AuthResult.Success(userId = 1, sessionId = "s", username = "admin", displayName = "Admin")

        server.enqueue(sessionExpiredEnvelope())
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"result\":{}}"))

        val response = executeRegister()

        assertEquals(200, response.code)
        assertEquals("{\"result\":{}}", response.body?.string())
        response.close()
        // Original + one retry.
        assertEquals(2, server.requestCount)
        // Exactly one re-auth network call.
        coVerify(exactly = 1) { odooClient.authenticate(any(), any(), any(), any(), any()) }
        // Retried request carried the fence marker (proves the cookie-refreshed replay).
        server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("1", retried.getHeader(SessionReauthenticator.RETRY_MARKER_HEADER))
    }

    // AC3.2 — persistent session-expired body stops at the cap, no loop

    @Test
    fun `Given persistent session-expired body when re-auth keeps succeeding then only one retry is made`() = runTest {
        val account = accountForMockServer()
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        every { encryptedPrefs.getPassword(account.id) } returns "stored-pass"
        coEvery {
            odooClient.authenticate(any(), any(), any(), any(), any())
        } returns AuthResult.Success(userId = 1, sessionId = "s", username = "admin", displayName = "Admin")

        // Server never recovers — always the session-expired envelope.
        server.enqueue(sessionExpiredEnvelope())
        server.enqueue(sessionExpiredEnvelope())
        server.enqueue(sessionExpiredEnvelope())

        val response = executeRegister()

        assertEquals(200, response.code)
        // The surfaced body is still the session-expired envelope (caller then throws IOException).
        assertEquals(true, response.body?.string()?.contains("SessionExpiredException"))
        response.close()
        // Original + exactly one retry, then give up. No infinite loop.
        assertEquals(2, server.requestCount)
        // Re-auth attempted exactly once (the retry is fenced by the marker header).
        coVerify(exactly = 1) { odooClient.authenticate(any(), any(), any(), any(), any()) }
    }

    // AC3.3 — bad credentials stop, clear session, emit re-login, no password re-send loop

    @Test
    fun `Given invalid stored credentials when re-auth then clears session emits re-login and does not retry`() = runTest {
        val account = accountForMockServer()
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        every { encryptedPrefs.getPassword(account.id) } returns "old-pass"
        coEvery {
            odooClient.authenticate(any(), any(), any(), any(), any())
        } returns AuthResult.Error("bad creds", AuthResult.ErrorType.INVALID_CREDENTIALS)

        server.enqueue(sessionExpiredEnvelope())

        val response = executeRegister()

        assertEquals(200, response.code)
        response.close()
        // No retry: only the original request reached the server.
        assertEquals(1, server.requestCount)
        // Re-auth attempted exactly once (the known-bad password is not hammered).
        coVerify(exactly = 1) { odooClient.authenticate(any(), any(), any(), any(), any()) }
        // Stale session cleared + re-login signalled.
        verify { odooClient.clearCookies(any()) }
        verify { reloginSignal.request(accountId = account.id, reason = ReloginReason.INVALID_CREDENTIALS) }
    }

    // AC3.4 — host/scheme safety: non-matching or non-https host declines re-auth

    @Test
    fun `Given no account matches request host when session expires then engine declines without password POST`() {
        coEvery { accountDao.getAllAccountsList() } returns
            listOf(httpsAccount(id = "other", host = "different.example.com"))

        val didReauth = reauthenticator.reauthenticateForHost("notmatching.example.com")

        assertFalse(didReauth)
        verify(exactly = 0) { encryptedPrefs.getPassword(any()) }
        coVerify(exactly = 0) { odooClient.authenticate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Given account host stored as http when session expires then engine declines`() {
        // Account resolves by host but its stored URL is not https -> re-auth refused.
        val account = OdooAccount(
            id = "acc-http",
            serverUrl = "http://plain.example.com",
            database = "db",
            username = "admin",
            displayName = "Admin",
        )
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)

        val didReauth = reauthenticator.reauthenticateForHost("plain.example.com")

        assertFalse(didReauth)
        coVerify(exactly = 0) { odooClient.authenticate(any(), any(), any(), any(), any()) }
    }

    // AC3.5 — single-flight: concurrent session expiries on the same host do not overlap in re-auth

    @Test
    fun `Given two concurrent session expiries for same host when re-auth then authenticate calls never overlap`() {
        val account = httpsAccount(id = "sf", host = "sf.example.com")
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        every { encryptedPrefs.getPassword(account.id) } returns "stored-pass"

        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { odooClient.authenticate(any(), any(), any(), any(), any()) } coAnswers {
            val now = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
            Thread.sleep(50) // widen the window so an unguarded impl would overlap
            inFlight.decrementAndGet()
            AuthResult.Success(userId = 1, sessionId = "s", username = "admin", displayName = "Admin")
        }

        // Two real threads driving the re-auth engine directly against the same host in parallel.
        val threads = (1..2).map {
            Thread { reauthenticator.reauthenticateForHost("sf.example.com") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // The per-host mutex must serialize re-auth: at no point did two authenticate calls overlap.
        assertEquals(1, maxConcurrent.get())
    }

    // Circuit breaker — after N transient failures, auto re-auth disabled + re-login signal

    @Test
    fun `Given repeated transient re-auth failures when threshold reached then circuit opens and signals`() = runTest {
        val account = httpsAccount(id = "cb", host = "cb.example.com")
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        every { encryptedPrefs.getPassword(account.id) } returns "stored-pass"
        coEvery {
            odooClient.authenticate(any(), any(), any(), any(), any())
        } returns AuthResult.Error("timeout", AuthResult.ErrorType.NETWORK_ERROR)

        // Drive the engine directly N times.
        repeat(SessionReauthenticator.MAX_CONSECUTIVE_FAILURES) {
            val didReauth = reauthenticator.reauthenticateForHost("cb.example.com")
            assertFalse(didReauth) // transient failure never retries
        }

        val reasonSlot = slot<ReloginReason>()
        verify {
            reloginSignal.request(accountId = account.id, reason = capture(reasonSlot))
        }
        assertEquals(ReloginReason.REAUTH_CIRCUIT_OPEN, reasonSlot.captured)

        // Once open, further expiries are declined without any re-auth attempt.
        coVerify(exactly = SessionReauthenticator.MAX_CONSECUTIVE_FAILURES) {
            odooClient.authenticate(any(), any(), any(), any(), any())
        }
        val afterOpen = reauthenticator.reauthenticateForHost("cb.example.com")
        assertFalse(afterOpen)
        // Still exactly N calls — none after the circuit opened.
        coVerify(exactly = SessionReauthenticator.MAX_CONSECUTIVE_FAILURES) {
            odooClient.authenticate(any(), any(), any(), any(), any())
        }
    }

    // Non-expiry success bodies must pass straight through with no re-auth.

    @Test
    fun `Given successful 200 result body when request runs then no re-auth and body untouched`() = runTest {
        val account = accountForMockServer()
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"result\":{\"tenant_id\":\"t1\"}}"))

        val response = executeRegister()

        assertEquals(200, response.code)
        assertEquals("{\"result\":{\"tenant_id\":\"t1\"}}", response.body?.string())
        response.close()
        assertEquals(1, server.requestCount)
        coVerify(exactly = 0) { odooClient.authenticate(any(), any(), any(), any(), any()) }
    }

    // A genuine transport-level 401 is still honoured (belt-and-suspenders).

    @Test
    fun `Given genuine 401 then 200 when request runs then re-auths once and retry succeeds`() = runTest {
        val account = accountForMockServer()
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        every { encryptedPrefs.getPassword(account.id) } returns "stored-pass"
        coEvery {
            odooClient.authenticate(any(), any(), any(), any(), any())
        } returns AuthResult.Success(userId = 1, sessionId = "s", username = "admin", displayName = "Admin")

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"result\":{}}"))

        val response = executeRegister()

        assertEquals(200, response.code)
        response.close()
        assertEquals(2, server.requestCount)
        coVerify(exactly = 1) { odooClient.authenticate(any(), any(), any(), any(), any()) }
    }


    // ──────────────────────────────────────────────────────────
    // Story 8-2 (P0-3) Hazard A — re-auth must bind the TAGGED account, not the MRU one
    // ──────────────────────────────────────────────────────────
    //
    // `resolveAccountForHost` takes `firstOrNull` over `lastLogin DESC`; its own KDoc admitted "the
    // most-recently-used one is chosen". With accounts A (MRU) and B on one host, B's expired POST
    // re-authenticated **A**, and the interceptor then replayed **B's** request carrying A's cookie:
    // the server created a row for A, the client returned success for B, and the response's tenant
    // id was written onto B's row. Silent cross-account corruption of the routing key.

    @Test
    fun `Given a tagged request when the session expires then the TAGGED account is re-authenticated`() = runTest {
        val host = server.hostName + ":" + server.port
        val mru = OdooAccount(
            id = "acc-A", serverUrl = "https://$host", database = "db",
            username = "userA", displayName = "A",
        )
        val tagged = OdooAccount(
            id = "acc-B", serverUrl = "https://$host", database = "db",
            username = "userB", displayName = "B",
        )
        // `lastLogin DESC` puts A first, so host resolution would pick A.
        coEvery { accountDao.getAllAccountsList() } returns listOf(mru, tagged)
        coEvery { accountDao.getAccountById("acc-B") } returns tagged
        every { encryptedPrefs.getPassword(any()) } returns "pw"
        coEvery { odooClient.authenticate(any(), any(), any(), any(), any()) } returns
            AuthResult.Success(userId = 2, sessionId = "s", username = "userB", displayName = "B")

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":100,"message":"Odoo Session Expired","data":{"name":"odoo.http.SessionExpiredException"}}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""))

        val request = Request.Builder()
            .url(server.url("/woow_fcm_push/register"))
            .post("{}".toRequestBody())
            .tag(
                io.woowtech.odoo.data.repository.FcmRequestAccount::class.java,
                io.woowtech.odoo.data.repository.FcmRequestAccount("acc-B"),
            )
            .build()
        client.newCall(request).execute().close()

        // The decisive assertion: the account id passed to authenticate is B's, not the MRU A's.
        val accountIdSlot = slot<String>()
        coVerify { odooClient.authenticate(capture(accountIdSlot), any(), any(), any(), any()) }
        assertEquals("acc-B", accountIdSlot.captured)
    }

    @Test
    fun `Given a tagged request when replayed then it carries the FRESH cookie not the stale one`() = runTest {
        // `request.newBuilder()` reuses the original headers, and the FCM client deliberately has no
        // CookieJar — so without an explicit re-resolve the replay re-presents the STALE cookie and
        // the re-authentication accomplishes nothing at all.
        val host = server.hostName + ":" + server.port
        val account = OdooAccount(
            id = "acc-B", serverUrl = "https://$host", database = "db",
            username = "userB", displayName = "B",
        )
        coEvery { accountDao.getAllAccountsList() } returns listOf(account)
        coEvery { accountDao.getAccountById("acc-B") } returns account
        every { encryptedPrefs.getPassword(any()) } returns "pw"
        coEvery { odooClient.authenticate(any(), any(), any(), any(), any()) } returns
            AuthResult.Success(userId = 2, sessionId = "s", username = "userB", displayName = "B")

        val freshProvider = object : io.woowtech.odoo.data.repository.SessionCookieProvider {
            override fun getCookiesForAccount(accountId: String) = listOf(
                okhttp3.Cookie.Builder().name("session_id").value("FRESH").domain(server.hostName).build(),
            )
        }
        val taggedClient = OkHttpClient.Builder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .addInterceptor(SessionReauthInterceptor(reauthenticator, freshProvider))
            .build()

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":100,"message":"Odoo Session Expired","data":{"name":"odoo.http.SessionExpiredException"}}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""))

        val request = Request.Builder()
            .url(server.url("/woow_fcm_push/register"))
            .post("{}".toRequestBody())
            .header("Cookie", "session_id=STALE")
            .tag(
                io.woowtech.odoo.data.repository.FcmRequestAccount::class.java,
                io.woowtech.odoo.data.repository.FcmRequestAccount("acc-B"),
            )
            .build()
        taggedClient.newCall(request).execute().close()

        // Read at the WIRE, via MockWebServer — the only place that tells the truth about headers.
        assertEquals("session_id=STALE", server.takeRequest().getHeader("Cookie"))
        assertEquals("session_id=FRESH", server.takeRequest().getHeader("Cookie"))
    }
}
