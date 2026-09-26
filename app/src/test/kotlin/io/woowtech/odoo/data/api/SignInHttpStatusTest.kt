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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A non-200 sign-in response keeps its HTTP status for the localized login message (iOS b9ebd0d parity). */
class SignInHttpStatusTest {
    private fun respond(code: Int, body: String, type: String) = { request: Request ->
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody(type.toMediaType())).build()
    }

    private fun signIn(brand: String, isolated: Boolean = false, handler: (Request) -> Response): AuthResult {
        val fixture = MockOnlyHttpFixture(handler)
        val api = OdooJsonRpcClient(fixture.client, AppBrand.forCode(brand), fixture.client)
        val result = runBlocking {
            if (isolated) api.authenticateApporoIsolated("https://fixture.test", "db", "user", "mock-password")
            else api.authenticate("https://fixture.test", "db", "user", "mock-password")
        }
        assertEquals(1, fixture.requests.get())
        assertEquals(0, fixture.dnsCalls.get())
        assertEquals(0, fixture.connectCalls.get())
        return result
    }

    private val cloudflare530 = respond(530, "<html>error code: 1033</html>", "text/html")

    @Test
    fun `Given 530 sign-in response in either brand then server error keeps HTTP status`() {
        for ((brand, isolated) in listOf("woowtech" to false, "apporo" to false, "apporo" to true)) {
            val result = signIn(brand, isolated, cloudflare530)
            assertTrue(result is AuthResult.Error, "$brand isolated=$isolated: $result")
            result as AuthResult.Error
            assertEquals(AuthResult.ErrorType.SERVER_ERROR, result.type, "$brand isolated=$isolated")
            assertEquals(530, result.httpStatus, "$brand isolated=$isolated")
        }
    }

    @Test
    fun `Given 500 sign-in response with JSON body then status still wins over the body`() {
        for (brand in listOf("woowtech", "apporo")) {
            val result = signIn(brand, handler = respond(500, """{"jsonrpc":"2.0","id":1,"result":{"uid":7}}""", "application/json"))
            assertEquals(500, (result as AuthResult.Error).httpStatus, brand)
            assertEquals(AuthResult.ErrorType.SERVER_ERROR, result.type, brand)
        }
    }

    @Test
    fun `Given Odoo JSON-RPC error over HTTP 200 then existing error mapping has no status`() {
        val body = """{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error","data":{"name":"builtins.RuntimeError","message":"boom"}}}"""
        val woow = signIn("woowtech", handler = respond(200, body, "application/json")) as AuthResult.Error
        assertEquals(AuthResult.Error("boom", AuthResult.ErrorType.SERVER_ERROR), woow)
        assertNull(woow.httpStatus)
        val apporo = signIn("apporo", handler = respond(200, body, "application/json")) as AuthResult.Error
        assertEquals(AuthResult.Error("Sign-in request rejected", AuthResult.ErrorType.SERVER_ERROR), apporo)
        assertNull(apporo.httpStatus)
    }
}
