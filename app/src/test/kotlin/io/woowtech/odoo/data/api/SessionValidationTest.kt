package io.woowtech.odoo.data.api

import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.testutil.MockOnlyHttpFixture
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * iOS D1/D5 parity (2026-09-30): the client can prove that a stored session still belongs to an
 * account (uid AND db, failing closed) and can revoke a session server-side, both through a
 * cookie-less client that never publishes into the shared jar.
 */
class SessionValidationTest {
    private val seen = mutableListOf<Request>()

    private fun api(code: Int = 200, body: String = "", type: String = "application/json"): Pair<OdooJsonRpcClient, MockOnlyHttpFixture> {
        val fixture = MockOnlyHttpFixture { request ->
            seen += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Set-Cookie", "session_id=server-new-sid; Path=/; HttpOnly")
                .body(body.toResponseBody(type.toMediaType())).build()
        }
        return OdooJsonRpcClient(fixture.client, AppBrand.forCode("apporo"), fixture.client) to fixture
    }

    private fun info(uid: String, db: String?) =
        """{"jsonrpc":"2.0","id":1,"result":{"uid":$uid,${db?.let { "\"db\":\"$it\"," } ?: ""}"name":"B"}}"""

    private fun belongs(body: String, code: Int = 200, sid: String = "b-sid-fixture"): Boolean {
        val (client, fixture) = api(code, body)
        val result = runBlocking { client.sessionBelongsTo("https://fixture.test", sid, 22, "db-b") }
        assertEquals(0, fixture.dnsCalls.get())
        assertEquals(0, fixture.connectCalls.get())
        // Nothing reaches the shared jar, even when the server sets a cookie.
        assertNull(client.getSessionId("fixture.test"))
        return result
    }

    @Test
    fun `Given same uid and db then session belongs and request carries only that session`() {
        assertTrue(belongs(info("22", "db-b")))
        val request = seen.single()
        assertEquals("/web/session/get_session_info", request.url.encodedPath)
        assertEquals("session_id=b-sid-fixture", request.header("Cookie"))
    }

    @Test
    fun `Given missing db then fail closed`() = assertFalse(belongs(info("22", null)))

    @Test
    fun `Given different db then fail closed`() = assertFalse(belongs(info("22", "db-a")))

    @Test
    fun `Given different or null uid then fail closed`() {
        assertFalse(belongs(info("11", "db-b")))
        assertFalse(belongs(info("null", "db-b")))
    }

    @Test
    fun `Given expired session error, non-200 or HTML then fail closed`() {
        assertFalse(belongs("""{"jsonrpc":"2.0","id":1,"error":{"code":100,"message":"Odoo Session Expired","data":{"name":"odoo.http.SessionExpiredException"}}}"""))
        assertFalse(belongs(info("22", "db-b"), code = 530))
        assertFalse(belongs("<html>maintenance</html>"))
    }

    @Test
    fun `Given a session token with header metacharacters then no request is sent`() {
        val (client, fixture) = api(body = info("22", "db-b"))
        assertFalse(runBlocking { client.sessionBelongsTo("https://fixture.test", "a;b", 22, "db-b") })
        assertFalse(runBlocking { client.revokeSession("https://fixture.test", "a\r\nX: y") })
        assertEquals(0, fixture.requests.get())
    }

    @Test
    fun `Given revoke then destroy is posted with only that session and never throws`() {
        val (client, _) = api(body = """{"jsonrpc":"2.0","id":1,"result":null}""")
        assertTrue(runBlocking { client.revokeSession("https://fixture.test", "old-sid") })
        val request = seen.single()
        assertEquals("/web/session/destroy", request.url.encodedPath)
        assertEquals("session_id=old-sid", request.header("Cookie"))
        val (failing, _) = api(code = 502)
        assertFalse(runBlocking { failing.revokeSession("https://fixture.test", "old-sid") })
    }

    private fun ownership(body: String, code: Int = 200): SessionOwnership {
        val (client, _) = api(code, body)
        return runBlocking { client.sessionOwnership("https://fixture.test", "b-sid-fixture", 22, "db-b") }
    }

    @Test
    fun `Given only a server answer about the session then ownership is evidence, anything else is Unknown`() {
        // pi 1001c P1: only a real answer proves anything; a failure to ask must never read as "not yours".
        assertEquals(SessionOwnership.Belongs, ownership(info("22", "db-b")))
        assertEquals(SessionOwnership.ProvenMismatch, ownership(info("11", "db-b")))
        assertEquals(SessionOwnership.ProvenMismatch, ownership(info("22", "db-a")))
        assertEquals(SessionOwnership.ProvenMismatch,
            ownership("""{"jsonrpc":"2.0","id":1,"error":{"code":100,"message":"Odoo Session Expired","data":{"name":"odoo.http.SessionExpiredException"}}}"""))
        assertEquals(SessionOwnership.Unknown, ownership(info("22", "db-b"), code = 530))
        assertEquals(SessionOwnership.Unknown, ownership("<html>maintenance</html>"))
        assertEquals(SessionOwnership.Unknown, ownership(info("22", null)))
        assertEquals(SessionOwnership.Unknown, ownership("""{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error","data":{"name":"builtins.Exception"}}}"""))
    }
}
