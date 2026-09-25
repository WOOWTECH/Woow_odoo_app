package io.woowtech.odoo.testutil

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic responses only; no request may proceed to DNS or a socket, even on fixture mismatch. */
class MockOnlyHttpFixture(private val handler: (Request) -> Response = { throw AssertionError("Unexpected mock request") }) {
    val requests = AtomicInteger()
    val dnsCalls = AtomicInteger()
    val connectCalls = AtomicInteger()
    val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .addInterceptor { chain -> requests.incrementAndGet(); handler(chain.request()) }
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                dnsCalls.incrementAndGet()
                throw AssertionError("DNS forbidden in mock fixture")
            }
        })
        .eventListener(object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connectCalls.incrementAndGet()
                throw AssertionError("Socket forbidden in mock fixture")
            }
        }).build()
}

/** Only this test's owned MockWebServer endpoint is reachable; redirects cannot escape it. */
fun OkHttpClient.Builder.onlyLoopback(server: MockWebServer): OkHttpClient.Builder {
    val endpoint = server.url("/")
    return addInterceptor { chain ->
        val url = chain.request().url
        if (url.host != endpoint.host && url.host != "127.0.0.1" || url.port != endpoint.port || url.scheme != "http") {
            throw AssertionError("Request outside owned loopback fixture")
        }
        chain.proceed(chain.request())
    }.dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != endpoint.host) throw AssertionError("External DNS forbidden in loopback fixture")
            return listOf(InetAddress.getLoopbackAddress())
        }
    }).eventListener(object : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            if (!inetSocketAddress.address.isLoopbackAddress || inetSocketAddress.port != endpoint.port || proxy != Proxy.NO_PROXY) {
                throw AssertionError("Socket outside owned loopback fixture")
            }
        }
    }).proxy(Proxy.NO_PROXY)
}
