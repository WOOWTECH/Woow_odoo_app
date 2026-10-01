package io.woowtech.odoo.data.api

import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * demo111 live run 2026-09-30 (B4): on the WOOW brand, adding a second account on the same host killed
 * the first account's server session. The sign-in request carried the host's stored `session_id`, so
 * Odoo re-authenticated that session as the new user and rotated it away. A sign-in must never send
 * another account's session; the new account's session still lands in the native jar.
 *
 * Runs through OkHttp's real cookie bridge: requests to `https://fixture.test` are rewritten to plain
 * HTTP on this test's own loopback MockWebServer, keeping the host `fixture.test` so the client's jar
 * keys stay what production uses. Nothing else is reachable.
 */
class WoowSignInSessionIsolationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OdooJsonRpcClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val port = server.port
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.host != "fixture.test" || url.scheme != "https") throw AssertionError("Unexpected request $url")
                chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").port(port).build()).build())
            }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != "fixture.test") throw AssertionError("External DNS forbidden")
                    return listOf(InetAddress.getLoopbackAddress())
                }
            })
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    if (!inetSocketAddress.address.isLoopbackAddress || inetSocketAddress.port != port) {
                        throw AssertionError("Socket outside owned loopback fixture")
                    }
                }
            })
            .proxy(Proxy.NO_PROXY)
            .build()
        api = OdooJsonRpcClient(http, AppBrand.forCode("woowtech"), http)
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun signInReply(sid: String, uid: Int) = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", "session_id=$sid; Path=/; HttpOnly")
            .setBody("""{"jsonrpc":"2.0","id":1,"result":{"uid":$uid,"name":"Fixture"}}""")
    )

    private fun login(user: String): AuthResult =
        runBlocking { api.authenticate("https://fixture.test", "demo111", user, "mock-password") }

    private fun take(): RecordedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))

    @Test
    fun `Given account A signed in on the host when account B signs in then B's request carries no session of A`() {
        signInReply("sid-A", 8)
        assertTrue(login("a") is AuthResult.Success)
        take()
        signInReply("sid-B", 9)

        login("b")

        assertNull(take().getHeader("Cookie"), "B's sign-in must not send A's session_id")
    }

    @Test
    fun `Given A then B sign in when B succeeds then the native jar holds B's session and B's result carries it`() {
        signInReply("sid-A", 8)
        assertEquals("sid-A", (login("a") as AuthResult.Success).sessionId)
        signInReply("sid-B", 9)

        val b = login("b")

        assertTrue(b is AuthResult.Success)
        assertEquals("sid-B", (b as AuthResult.Success).sessionId)
        assertEquals("sid-B", api.getSessionId("fixture.test"))
    }

    @Test
    fun `Given a stored session when the same account signs in again then the request is still cookie-less`() {
        signInReply("sid-A", 8)
        login("a")
        take()
        signInReply("sid-A2", 8)

        login("a")

        assertNull(take().getHeader("Cookie"), "a re-sign-in must not re-authenticate the stored session in place")
        assertEquals("sid-A2", api.getSessionId("fixture.test"))
    }
}
