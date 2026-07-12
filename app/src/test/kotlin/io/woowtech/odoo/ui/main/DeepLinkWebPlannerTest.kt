package io.woowtech.odoo.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [DeepLinkWebPlanner] — the pure host-gating and warm-fragment logic used by the
 * single-view WebView. These encode the load-gate ("apply only on the target host") and the
 * warm-start hash-navigation behaviour.
 */
class DeepLinkWebPlannerTest {

    private val serverA = "https://a-odoo.woowtech.io"
    private val serverB = "https://b-odoo.woowtech.io"

    // ── host gating (cross-account: host==B, never A) ──────────────────────────

    @Test
    fun `Given a page loaded on B when hostMatches target B then true`() {
        assertTrue(
            DeepLinkWebPlanner.hostMatches(
                loadedUrl = "https://b-odoo.woowtech.io/web#active_id=mail.channel_7",
                targetServerUrl = serverB,
            ),
        )
    }

    @Test
    fun `Given a page still on A when hostMatches target B then false (never applied on A)`() {
        // The crux negative assertion: while the WebView is still showing A's host, a deep link
        // targeting B must NOT be considered applicable.
        assertFalse(
            DeepLinkWebPlanner.hostMatches(
                loadedUrl = "https://a-odoo.woowtech.io/web",
                targetServerUrl = serverB,
            ),
        )
    }

    @Test
    fun `Given null loaded url when hostMatches then false`() {
        assertFalse(DeepLinkWebPlanner.hostMatches(loadedUrl = null, targetServerUrl = serverB))
    }

    @Test
    fun `Given differing case hosts when hostMatches then true`() {
        assertTrue(
            DeepLinkWebPlanner.hostMatches(
                loadedUrl = "https://B-ODOO.woowtech.io/web",
                targetServerUrl = serverB,
            ),
        )
    }

    // ── fragment extraction (warm navigation trigger) ─────────────────────────

    @Test
    fun `Given fragment url when fragmentOf then returns fragment`() {
        assertEquals(
            "active_id=mail.channel_7",
            DeepLinkWebPlanner.fragmentOf("/web#active_id=mail.channel_7"),
        )
    }

    @Test
    fun `Given url without fragment when fragmentOf then null`() {
        assertNull(DeepLinkWebPlanner.fragmentOf("/web/login"))
    }

    @Test
    fun `Given url with empty fragment when fragmentOf then null`() {
        assertNull(DeepLinkWebPlanner.fragmentOf("/web#"))
    }

    // ── warm-start navigation JS ──────────────────────────────────────────────

    @Test
    fun `Given fragment when buildFragmentNavJs then sets hash and dispatches hashchange`() {
        val js = DeepLinkWebPlanner.buildFragmentNavJs("active_id=mail.channel_7")

        assertTrue(js.contains("location.hash"), "must set location.hash")
        assertTrue(js.contains("hashchange"), "must dispatch hashchange for the warm SPA case")
        assertTrue(js.contains("active_id=mail.channel_7"), "must carry the target fragment")
    }

    @Test
    fun `Given fragment with quotes when buildFragmentNavJs then value is escaped`() {
        val js = DeepLinkWebPlanner.buildFragmentNavJs("id=1\"; alert('x')//")

        // The double-quote from the payload must be escaped (backslash-quote), so it cannot close
        // the JS string literal and break out into executable code.
        assertTrue(js.contains("\\\""), "payload double-quote must be JSON-escaped as \\\"")
    }

    @Test
    fun `Given fragment with angle brackets when buildFragmentNavJs then they are unicode-escaped`() {
        val js = DeepLinkWebPlanner.buildFragmentNavJs("id=<script>")

        assertFalse(js.contains("<script>"), "angle brackets must be unicode-escaped, not raw")
        assertTrue(js.contains("\\u003C"), "'<' must be escaped to \\u003C")
    }

    /**
     * Warm-start scenario decomposed to its two pure decisions: the loaded page is already on the
     * target host, and the deep link is fragment-based -> a warm hash navigation is warranted
     * (rather than a full reload, which is a no-op inside the SPA).
     */
    @Test
    fun `Given already on target host and fragment link then warm fragment nav is warranted`() {
        val loaded = "https://b-odoo.woowtech.io/web#active_id=mail.channel_1"
        val pending = "/web#active_id=mail.channel_9"

        val onTargetHost = DeepLinkWebPlanner.hostMatches(loadedUrl = loaded, targetServerUrl = serverB)
        val fragment = DeepLinkWebPlanner.fragmentOf(pending)

        assertTrue(onTargetHost)
        assertEquals("active_id=mail.channel_9", fragment)
    }
}
