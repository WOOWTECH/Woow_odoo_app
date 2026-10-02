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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * pi 1001b Android P1 (redirect): the WOOW sign-in client followed redirects and parsed the FINAL
 * response's `Set-Cookie` against the ORIGINAL request host, so a 302 to another host answering 200 uid +
 * session_id was accepted as the original host's session. A WOOW sign-in must not follow redirects (like
 * the Apporo isolated client): any 3xx is a failure and the jar is untouched.
 */
class WoowSignInRedirectTest {
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
                    // other.test is the redirect target; both stay on the owned loopback server.
                    if (hostname != "fixture.test" && hostname != "other.test") throw java.net.UnknownHostException(hostname)
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

    private fun login(user: String): AuthResult =
        runBlocking { api.authenticate("https://fixture.test", "demo111", user, "mock-password") }

    private fun okWithSession(uid: Int, sid: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .addHeader("Set-Cookie", "session_id=$sid; Path=/; HttpOnly")
        .setBody("""{"jsonrpc":"2.0","id":1,"result":{"uid":$uid,"name":"Fixture"}}""")

    @Test
    fun `Given A in the jar when B's sign-in is redirected to another host that answers uid and a session then B fails and A stays`() {
        server.enqueue(okWithSession(8, "sid-A"))
        assertEquals("sid-A", (login("a") as AuthResult.Success).sessionId)
        api.publishSession("https://fixture.test", "sid-A") // the repository's commit (pi 1001f P1)
        server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "http://other.test:${server.port}/web/session/authenticate"))
        server.enqueue(okWithSession(9, "sid-other-host"))

        val b = login("b")

        assertTrue(b is AuthResult.Error, "a redirected sign-in must not succeed: $b")
        assertEquals("sid-A", api.getSessionId("fixture.test"), "the jar must not take the other host's session")
        assertEquals(2, server.requestCount, "the redirect must not be followed")
    }

    @Test
    fun `Given an empty jar when the sign-in answers 302 then sign-in fails with the HTTP status`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/web/login"))
        server.enqueue(okWithSession(8, "sid-after-redirect"))

        val a = login("a")

        assertTrue(a is AuthResult.Error, "a 3xx is not a sign-in: $a")
        assertEquals(302, (a as AuthResult.Error).httpStatus)
        assertEquals(1, server.requestCount)
    }
}
