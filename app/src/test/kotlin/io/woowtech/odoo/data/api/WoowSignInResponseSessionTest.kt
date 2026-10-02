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
 * pi 0930b Android P1: a WOOW sign-in took its session id from the persistent host cookie store, so a
 * 200 uid-B answer without a usable `Set-Cookie` handed account B account A's stored session. The SID
 * must come only from THIS response (iOS isolated response-cookie rule); without one the sign-in fails
 * and nothing about A changes. Same loopback cookie-bridge fixture as [WoowSignInSessionIsolationTest].
 */
class WoowSignInResponseSessionTest {
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

    private fun reply(uid: Int, setCookie: String?) = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").apply {
            if (setCookie != null) addHeader("Set-Cookie", setCookie)
        }.setBody("""{"jsonrpc":"2.0","id":1,"result":{"uid":$uid,"name":"Fixture"}}""")
    )

    private fun login(user: String): AuthResult =
        runBlocking { api.authenticate("https://fixture.test", "demo111", user, "mock-password") }

    private fun signInA() {
        reply(8, "session_id=sid-A; Path=/; HttpOnly")
        assertEquals("sid-A", (login("a") as AuthResult.Success).sessionId)
        api.publishSession("https://fixture.test", "sid-A") // the repository's commit (pi 1001f P1)
    }

    @Test
    fun `Given A in the jar when B's 200 uid answer has no Set-Cookie then B fails and A stays in the jar`() {
        signInA()
        reply(9, null)

        val b = login("b")

        assertTrue(b is AuthResult.Error, "B must not succeed on A's stored session: $b")
        assertEquals(AuthResult.ErrorType.SESSION_EXPIRED, (b as AuthResult.Error).type)
        assertEquals("sid-A", api.getSessionId("fixture.test"))
    }

    @Test
    fun `Given A in the jar when B's Set-Cookie is already expired then B fails and A stays in the jar`() {
        signInA()
        reply(9, "session_id=sid-B; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")

        val b = login("b")

        assertTrue(b is AuthResult.Error, "an expired cookie is no session: $b")
        assertEquals(AuthResult.ErrorType.SESSION_EXPIRED, (b as AuthResult.Error).type)
        assertEquals("sid-A", api.getSessionId("fixture.test"), "an expired cookie must not replace A's session")
    }

    @Test
    fun `Given an empty jar when a 200 uid answer has no Set-Cookie then sign-in fails instead of succeeding with an empty SID`() {
        reply(8, null)

        val a = login("a")

        assertTrue(a is AuthResult.Error, "an empty session id is not a sign-in: $a")
        assertEquals(AuthResult.ErrorType.SESSION_EXPIRED, (a as AuthResult.Error).type)
    }

    @Test
    fun `Given a cookie that is not session_id when B signs in then B fails`() {
        signInA()
        reply(9, "frontend_lang=en_US; Path=/")

        assertTrue(login("b") is AuthResult.Error)
        assertEquals("sid-A", api.getSessionId("fixture.test"))
    }
}
