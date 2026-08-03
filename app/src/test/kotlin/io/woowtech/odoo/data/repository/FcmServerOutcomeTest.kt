package io.woowtech.odoo.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for [FcmServerOutcome.parse] — story 8-2 (P0-2).
 *
 * The review of the first implementation found that **no test exercised `Unreadable` at all**, which
 * was the most behaviour-changing branch of the new parser: register threw on it, so any server
 * whose `result` was not an object would have failed every registration forever. This class exists
 * so that branch can never go unexamined again.
 *
 * Rejection is keyed on the PRESENCE of an `error` key, never its message text: the plugin's own
 * tests assert only `assertIn('error', result)`, so the strings are not a contract we may depend on.
 */
class FcmServerOutcomeTest {

    private fun parse(body: String?) = FcmServerOutcome.parse(body)

    // ── Ok ────────────────────────────────────────────────────────────────

    @Test
    fun `Given a register success body then Ok`() {
        assertEquals(
            FcmServerOutcome.Ok,
            parse("""{"jsonrpc":"2.0","id":1,"result":{"device_id":7,"odoo_tenant_id":"t"}}"""),
        )
    }

    @Test
    fun `Given unregister success false then Ok not Rejected`() {
        // "You had no row" is the DESIRED post-logout state. Treating it as a rejection would
        // manufacture an alarm on every correct logout.
        assertEquals(FcmServerOutcome.Ok, parse("""{"jsonrpc":"2.0","id":1,"result":{"success":false}}"""))
    }

    @Test
    fun `Given an explicitly null error then Ok`() {
        assertEquals(FcmServerOutcome.Ok, parse("""{"jsonrpc":"2.0","error":null,"result":{}}"""))
    }

    @Test
    fun `Given a null error INSIDE result then Ok`() {
        assertEquals(FcmServerOutcome.Ok, parse("""{"jsonrpc":"2.0","result":{"error":null,"success":true}}"""))
    }

    // ── Rejected ──────────────────────────────────────────────────────────

    @Test
    fun `Given an error inside result then Rejected`() {
        assertInstanceOf(
            FcmServerOutcome.Rejected::class.java,
            parse("""{"jsonrpc":"2.0","id":1,"result":{"error":"Invalid fcm_token format"}}"""),
        )
    }

    @Test
    fun `Given an envelope error then Rejected`() {
        assertInstanceOf(
            FcmServerOutcome.Rejected::class.java,
            parse("""{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error"}}"""),
        )
    }

    @Test
    fun `Given a different rejection message then still Rejected`() {
        // Keyed on the KEY, not the text. The plugin's tests assert key presence only, so a message
        // change must not silently reclassify a rejection as success.
        assertInstanceOf(
            FcmServerOutcome.Rejected::class.java,
            parse("""{"jsonrpc":"2.0","result":{"error":"something nobody has written yet"}}"""),
        )
    }

    // ── Unreadable — the branch the first implementation shipped untested ──

    @Test
    fun `Given bodies that cannot be read then Unreadable`() {
        val unreadable = listOf(
            null,
            "",
            "   ",
            "<html>captive portal</html>",
            """{"jsonrpc":"2.0","id":1}""",            // no result at all
            """{"jsonrpc":"2.0","id":1,"result":null}""",
            """{"result":true}""",
            """{"result":[]}""",
            """{"result":"ok"}""",
            """[1,2,3]""",
            """"just a string"""",
        )
        unreadable.forEach { body ->
            assertEquals(
                FcmServerOutcome.Unreadable, parse(body),
                "expected Unreadable for: $body",
            )
        }
    }

    @Test
    fun `Given an unreadable body then it is NOT reported as Rejected`() {
        // The distinction is load-bearing: register treats Rejected as fatal and Unreadable as
        // acceptable-but-loud. Collapsing them would make a shape mismatch fail every registration
        // forever — strictly worse than the silent success this story replaced.
        assertInstanceOf(FcmServerOutcome.Unreadable::class.java, parse("""{"result":[]}"""))
    }
}
