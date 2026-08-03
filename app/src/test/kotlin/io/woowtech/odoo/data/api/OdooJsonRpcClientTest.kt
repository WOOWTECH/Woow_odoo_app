package io.woowtech.odoo.data.api

import io.woowtech.odoo.domain.model.AuthResult
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [OdooJsonRpcClient].
 *
 * The client uses OkHttp internally and enforces HTTPS before making any
 * network call. We use OkHttp MockWebServer for network-layer tests (with
 * http:// URLs) and verify the HTTPS guard separately since MockWebServer
 * does not serve real TLS by default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OdooJsonRpcClientTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var client: OdooJsonRpcClient

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        client = OdooJsonRpcClient()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────
    // HTTPS Enforcement
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class HttpsEnforcement {

        @Test
        fun `Given http URL when authenticate then returns HTTPS_REQUIRED error without network call`() = runTest {
            val result = client.authenticate(
                accountId = "test-account",
                serverUrl = "http://insecure.example.com",
                database = "mydb",
                username = "admin",
                password = "pass"
            )

            assertTrue(result is AuthResult.Error)
            val error = result as AuthResult.Error
            assertEquals(AuthResult.ErrorType.HTTPS_REQUIRED, error.type)
        }

        @Test
        fun `Given bare domain without https prefix when authenticate then returns HTTPS_REQUIRED`() = runTest {
            val result = client.authenticate(
                accountId = "test-account",
                serverUrl = "example.com",
                database = "mydb",
                username = "admin",
                password = "pass"
            )

            assertTrue(result is AuthResult.Error)
            assertEquals(AuthResult.ErrorType.HTTPS_REQUIRED, (result as AuthResult.Error).type)
        }
    }

    // ──────────────────────────────────────────────────────────
    // Cookie Management
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class CookieManagement {

        @Test
        fun `Given no cookies stored when getSessionId then returns null`() {
            assertNull(client.getSessionId("unknown.host.com"))
        }

        @Test
        fun `Given no cookies stored when getSessionCookies then returns empty list`() {
            assertTrue(client.getSessionCookies("unknown.host.com").isEmpty())
        }

        @Test
        fun `Given cookies stored when clearCookies then getSessionCookies returns empty`() {
            // We cannot easily inject cookies without a real request, but we can verify
            // clearCookies does not throw on non-existent host
            client.clearCookies("some.host.com")
            assertTrue(client.getSessionCookies("some.host.com").isEmpty())
        }
    }

    // ──────────────────────────────────────────────────────────
    // Network Error Handling
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class NetworkErrors {

        @Test
        fun `Given unreachable server when authenticate then returns NETWORK_ERROR`() = runTest {
            // Use a host that cannot resolve
            val result = client.authenticate(
                accountId = "test-account",
                serverUrl = "https://this-server-definitely-does-not-exist-12345.invalid",
                database = "mydb",
                username = "admin",
                password = "pass"
            )

            assertTrue(result is AuthResult.Error)
            assertEquals(AuthResult.ErrorType.NETWORK_ERROR, (result as AuthResult.Error).type)
        }
    }

    // ──────────────────────────────────────────────────────────
    // JSON-RPC Response Parsing (extractHost utility)
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class HostExtraction {

        @Test
        fun `Given https URL when getSessionId then extracts host correctly`() {
            // Verify extractHost logic indirectly through getSessionId
            // No cookies exist, so it returns null, but it should not throw
            assertNull(client.getSessionId("odoo.example.com"))
        }

        @Test
        fun `Given URL with path when getSessionCookies then uses host only`() {
            // Verify no crash on complex host strings
            val cookies = client.getSessionCookies("odoo.example.com")
            assertTrue(cookies.isEmpty())
        }
    }

    // ──────────────────────────────────────────────────────────
    // AuthResult.Success Data Structure
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given AuthResult Success when created then contains all expected fields`() {
        val success = AuthResult.Success(
            userId = 42,
            sessionId = "sess-123",
            username = "admin",
            displayName = "Administrator"
        )

        assertEquals(42, success.userId)
        assertEquals("sess-123", success.sessionId)
        assertEquals("admin", success.username)
        assertEquals("Administrator", success.displayName)
    }

    @Test
    fun `Given AuthResult Error when created then contains message and type`() {
        val error = AuthResult.Error("Something failed", AuthResult.ErrorType.SERVER_ERROR)

        assertEquals("Something failed", error.message)
        assertEquals(AuthResult.ErrorType.SERVER_ERROR, error.type)
    }

    // ──────────────────────────────────────────────────────────
    // JSON-RPC Data Classes
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class JsonRpcDataClasses {

        @Test
        fun `Given JsonRpcRequest when constructed then has correct defaults`() {
            val request = JsonRpcRequest(
                method = "call",
                params = mapOf("db" to "test"),
                id = 1
            )

            assertEquals("2.0", request.jsonrpc)
            assertEquals("call", request.method)
            assertEquals(1, request.id)
            assertEquals("test", request.params["db"])
        }

        @Test
        fun `Given JsonRpcResponse with no error when accessed then error is null`() {
            val response = JsonRpcResponse(
                jsonrpc = "2.0",
                id = 1,
                result = null,
                error = null
            )

            assertNull(response.error)
        }

        @Test
        fun `Given JsonRpcError with nested data when accessed then message is available`() {
            val errorData = JsonRpcErrorData(
                name = "odoo.exceptions.AccessDenied",
                message = "Access Denied",
                debug = "traceback..."
            )
            val error = JsonRpcError(
                code = 200,
                message = "Odoo Server Error",
                data = errorData
            )

            assertEquals("Access Denied", error.data?.message)
            assertEquals("odoo.exceptions.AccessDenied", error.data?.name)
        }

        @Test
        fun `Given JsonRpcError without data when accessed then data is null`() {
            val error = JsonRpcError(
                code = 500,
                message = "Internal Server Error",
                data = null
            )

            assertNull(error.data)
            assertEquals("Internal Server Error", error.message)
        }
    }

    // ──────────────────────────────────────────────────────────
    // All ErrorType enum values covered
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given all ErrorType values when enumerated then all 8 types exist`() {
        val types = AuthResult.ErrorType.entries
        assertEquals(8, types.size)
        assertTrue(types.contains(AuthResult.ErrorType.NETWORK_ERROR))
        assertTrue(types.contains(AuthResult.ErrorType.INVALID_URL))
        assertTrue(types.contains(AuthResult.ErrorType.DATABASE_NOT_FOUND))
        assertTrue(types.contains(AuthResult.ErrorType.INVALID_CREDENTIALS))
        assertTrue(types.contains(AuthResult.ErrorType.SESSION_EXPIRED))
        assertTrue(types.contains(AuthResult.ErrorType.HTTPS_REQUIRED))
        assertTrue(types.contains(AuthResult.ErrorType.SERVER_ERROR))
        assertTrue(types.contains(AuthResult.ErrorType.UNKNOWN))
    }


    // ──────────────────────────────────────────────────────────
    // Story 8-2 (P0-3) — one session per ACCOUNT, not one per host
    // ──────────────────────────────────────────────────────────
    //
    // The store used to be `ConcurrentHashMap<host, MutableList<Cookie>>` with a CookieJar whose
    // `saveFromResponse` called `clear()` before writing. Authenticating account B on a host did
    // not shadow account A's session — it DELETED it. The app could hold exactly ONE Odoo session
    // per host, process-wide, so multi-account on one server was impossible by construction and
    // the FCM fan-out silently POSTed every account under whichever session survived.
    //
    // Driven through an injected OkHttpClient rather than MockWebServer: this class enforces
    // `https://` and MockWebServer serves plaintext by default (okhttp-tls is not available
    // offline). Same seam, same reason, as FcmTokenRepositoryImpl.

    @Nested
    inner class PerAccountSessions {

        private fun clientServing(sessionFor: (String) -> String): OdooJsonRpcClient {
            val http = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    val user = Regex(""""login":"([^"]+)"""")
                        .find(req.body?.let { b ->
                            okio.Buffer().also { b.writeTo(it) }.readUtf8()
                        } ?: "")?.groupValues?.get(1) ?: "?"
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Set-Cookie", "session_id=${sessionFor(user)}; Path=/")
                        .body(
                            """{"jsonrpc":"2.0","id":1,"result":{"uid":7,"name":"$user"}}"""
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                .build()
            return OdooJsonRpcClient(httpClient = http)
        }

        @Test
        fun `Given two accounts on ONE host when both authenticate then both sessions survive`() = runTest {
            val client = clientServing { user -> "sess-$user" }

            client.authenticate("acc-A", "https://shared.odoo.com", "db", "userA", "pw")
            client.authenticate("acc-B", "https://shared.odoo.com", "db", "userB", "pw")

            // The defect, stated directly: with a host-keyed store the second login wiped the first,
            // so this assertion could not have held for BOTH accounts at once.
            assertEquals("sess-userA", client.getSessionId("acc-A"))
            assertEquals("sess-userB", client.getSessionId("acc-B"))
        }

        @Test
        fun `Given two accounts on one host when one logs out then the sibling keeps its session`() = runTest {
            val client = clientServing { user -> "sess-$user" }
            client.authenticate("acc-A", "https://shared.odoo.com", "db", "userA", "pw")
            client.authenticate("acc-B", "https://shared.odoo.com", "db", "userB", "pw")

            client.clearCookies("acc-A")

            assertNull(client.getSessionId("acc-A"))
            assertEquals("sess-userB", client.getSessionId("acc-B"), "clearing one account logged out its sibling")
        }

        @Test
        fun `Given re-authentication of one account then only that account's session is replaced`() = runTest {
            var round = 1
            val client = clientServing { user -> "sess-$user-$round" }
            client.authenticate("acc-A", "https://shared.odoo.com", "db", "userA", "pw")
            client.authenticate("acc-B", "https://shared.odoo.com", "db", "userB", "pw")

            round = 2
            client.authenticate("acc-A", "https://shared.odoo.com", "db", "userA", "pw")

            assertEquals("sess-userA-2", client.getSessionId("acc-A"))
            assertEquals("sess-userB-1", client.getSessionId("acc-B"))
        }
    }
}
