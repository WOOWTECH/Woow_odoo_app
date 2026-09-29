package io.woowtech.odoo.data.api

import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.testutil.MockOnlyHttpFixture
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * iOS demo111 D2/F2 parity (2026-09-30): Odoo 18 answers a wrong password with an HTTP 200 JSON-RPC
 * error named `odoo.exceptions.AccessDenied` ("Access Denied"). Every brand and entry point must map it
 * to INVALID_CREDENTIALS (localized "wrong username or password"), and a 200 body that is not JSON-RPC
 * must be a localized SERVER_ERROR instead of UNKNOWN (which the login card shows as raw text).
 */
class LoginErrorMappingParityTest {
    private fun respond(body: String, type: String = "application/json") = { request: Request ->
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toResponseBody(type.toMediaType())).build()
    }

    private val entries = listOf("woowtech" to false, "apporo" to false, "apporo" to true)

    private fun signIn(brand: String, isolated: Boolean, handler: (Request) -> Response): AuthResult {
        val fixture = MockOnlyHttpFixture(handler)
        val api = OdooJsonRpcClient(fixture.client, AppBrand.forCode(brand), fixture.client)
        val result = runBlocking {
            if (isolated) api.authenticateApporoIsolated("https://fixture.test", "db", "user", "mock-password")
            else api.authenticate("https://fixture.test", "db", "user", "mock-password")
        }
        assertEquals(0, fixture.dnsCalls.get())
        assertEquals(0, fixture.connectCalls.get())
        return result
    }

    private fun assertType(expected: AuthResult.ErrorType, body: String, type: String = "application/json") {
        for ((brand, isolated) in entries) {
            val result = signIn(brand, isolated, respond(body, type))
            assertTrue(result is AuthResult.Error, "$brand isolated=$isolated: $result")
            assertEquals(expected, (result as AuthResult.Error).type, "$brand isolated=$isolated: $result")
        }
    }

    @Test
    fun `Given Odoo 18 AccessDenied error then every entry maps to invalid credentials`() = assertType(
        AuthResult.ErrorType.INVALID_CREDENTIALS,
        """{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error","data":{"name":"odoo.exceptions.AccessDenied","message":"Access Denied","arguments":["Access Denied"]}}}""",
    )

    @Test
    fun `Given Access Denied message without exception name then every entry maps to invalid credentials`() = assertType(
        AuthResult.ErrorType.INVALID_CREDENTIALS,
        """{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error","data":{"message":"Access Denied"}}}""",
    )

    @Test
    fun `Given HTML page over HTTP 200 then every entry maps to localized server error`() = assertType(
        AuthResult.ErrorType.SERVER_ERROR, "<html><body>Maintenance</body></html>", "text/html",
    )

    @Test
    fun `Given empty body over HTTP 200 then every entry maps to localized server error`() = assertType(
        AuthResult.ErrorType.SERVER_ERROR, "",
    )

    @Test
    fun `Given WOOW database error then its existing mapping is unchanged`() {
        val body = """{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error","data":{"name":"psycopg2.OperationalError","message":"database \"nope\" does not exist"}}}"""
        val result = signIn("woowtech", false, respond(body)) as AuthResult.Error
        assertEquals(AuthResult.ErrorType.DATABASE_NOT_FOUND, result.type)
    }
}
