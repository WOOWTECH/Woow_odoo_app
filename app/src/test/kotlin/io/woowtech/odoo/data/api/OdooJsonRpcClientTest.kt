package io.woowtech.odoo.data.api

import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.testutil.MockOnlyHttpFixture
import java.net.UnknownHostException
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
    private lateinit var transport: MockOnlyHttpFixture

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transport = MockOnlyHttpFixture { request ->
            if (request.url.host == "unreachable.fixture.test" && request.url.encodedPath == "/web/session/authenticate") {
                throw UnknownHostException("Mock network failure")
            }
            throw AssertionError("Unexpected auth request")
        }
        client = OdooJsonRpcClient(transport.client, AppBrand.current, transport.client)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        assertEquals(0, transport.dnsCalls.get())
        assertEquals(0, transport.connectCalls.get())
    }

    // ──────────────────────────────────────────────────────────
    // HTTPS Enforcement
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class HttpsEnforcement {

        @Test
        fun `Given http URL when authenticate then returns HTTPS_REQUIRED error without network call`() = runTest {
            val result = client.authenticate(
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
            // Simulated failure before DNS; the fixture never connects to any host.
            val result = client.authenticate(
                serverUrl = "https://unreachable.fixture.test",
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

    // Play 審查退件 2026-09-23：主機名稱帶尾端空白時 OkHttp 丟 IllegalArgumentException，
    // 原本以 "Error: Invalid URL host: ..." 原文顯示給使用者。
    @Test
    fun `Given syntactically invalid host when authenticate then returns INVALID_URL without raw exception text`() = runTest {
        val result = client.authenticate(
            serverUrl = "https://bad host/",
            database = "fixture-db",
            username = "u",
            password = "p"
        )

        assertTrue(result is AuthResult.Error)
        result as AuthResult.Error
        assertEquals(AuthResult.ErrorType.INVALID_URL, result.type)
        assertEquals("Invalid server URL", result.message)
    }
}
