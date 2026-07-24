package io.woowtech.odoo.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [DeepLinkWebPlanner] — the pure host-gating and full-load planning logic used by the
 * single-view WebView. These encode the load-gate ("apply only on the target host") and the
 * always-full-load navigation behaviour (with apply-layer re-validation).
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

    // ── path parsing ──────────────────────────────────────────────────────────

    @Test
    fun `Given absolute odoo url when pathOf then returns odoo path`() {
        assertEquals("/odoo", DeepLinkWebPlanner.pathOf("https://b-odoo.woowtech.io/odoo"))
    }

    @Test
    fun `Given relative deep link when pathOf then returns path before fragment`() {
        assertEquals("/web", DeepLinkWebPlanner.pathOf("/web#action=mail.action_discuss"))
    }

    @Test
    fun `Given validated deep link when fullLoadUrl then preserves fragment`() {
        assertEquals(
            "https://b-odoo.woowtech.io/web#action=mail.action_discuss&active_id=discuss.channel_10",
            DeepLinkWebPlanner.fullLoadUrl(serverB, "/web#action=mail.action_discuss&active_id=discuss.channel_10"),
        )
    }

    // ── navigation plan: Odoo ≤16 hash router vs ≥17/18 path router ────────────

    @Test
    fun `Given Odoo18 SPA on odoo and web deep link then plan is FullLoad carrying the fragment`() {
        // The reported bug: on Odoo 18 the running SPA is on /odoo while the deep link targets /web.
        // Different paths -> must be a full load (Odoo 18's path router ignores a location.hash poke).
        val plan = DeepLinkWebPlanner.plan(
            currentUrl = "https://b-odoo.woowtech.io/odoo",
            serverUrl = serverB,
            deepLink = "/web#action=mail.action_discuss&active_id=discuss.channel_10",
        )
        assertTrue(plan is DeepLinkWebPlanner.NavPlan.FullLoad, "Odoo 18 (current /odoo, target /web) must be a full load, not a hash-poke")
        assertTrue(
            (plan as DeepLinkWebPlanner.NavPlan.FullLoad).url.contains("discuss.channel_10"),
            "the full-load URL must carry the channel fragment so Odoo migrates it at boot",
        )
    }

    @Test
    fun `Given form deep link on Odoo18 then plan is FullLoad to the record`() {
        val plan = DeepLinkWebPlanner.plan(
            currentUrl = "https://b-odoo.woowtech.io/odoo",
            serverUrl = serverB,
            deepLink = "/web#id=3&model=res.partner&view_type=form",
        )
        assertTrue(plan is DeepLinkWebPlanner.NavPlan.FullLoad)
        assertTrue((plan as DeepLinkWebPlanner.NavPlan.FullLoad).url.endsWith("/web#id=3&model=res.partner&view_type=form"))
    }

    @Test
    fun `Given already on same web path then plan is still FullLoad (no fragment-poke race)`() {
        // Even when the WebView appears to be on the same /web path, we must NOT hash-poke: that
        // path is a timing race (the /web->/odoo redirect may not have completed) and Odoo 18's
        // path router ignores the hash. Always a full load — Odoo migrates the hash at boot.
        val plan = DeepLinkWebPlanner.plan(
            currentUrl = "https://b-odoo.woowtech.io/web?db=dbB",
            serverUrl = serverB,
            deepLink = "/web#action=mail.action_discuss&active_id=discuss.channel_9",
        )
        assertTrue(plan is DeepLinkWebPlanner.NavPlan.FullLoad, "must always full-load, never hash-poke")
        assertTrue((plan as DeepLinkWebPlanner.NavPlan.FullLoad).url.contains("discuss.channel_9"))
    }

    @Test
    fun `Given no current url when plan then FullLoad (cold start)`() {
        val plan = DeepLinkWebPlanner.plan(currentUrl = null, serverUrl = serverB, deepLink = "/web#active_id=discuss.channel_9")
        assertTrue(plan is DeepLinkWebPlanner.NavPlan.FullLoad)
    }

    @Test
    fun `Given path-only deep link when plan then FullLoad`() {
        val plan = DeepLinkWebPlanner.plan(
            currentUrl = "https://b-odoo.woowtech.io/odoo",
            serverUrl = serverB,
            deepLink = "/web/login",
        )
        assertTrue(plan is DeepLinkWebPlanner.NavPlan.FullLoad)
    }

    /**
     * Warm path-only link (no `#fragment`) must still yield a full load. Regression guard for the
     * bug where the warm branch gated on a fragment being present and silently dropped path-only
     * links like "/web/login".
     */
    @Test
    fun `Given warm path-only link with no fragment when plan then FullLoad`() {
        val plan = DeepLinkWebPlanner.plan(
            // Warm: WebView already on the target host, same account, no reload.
            currentUrl = "https://b-odoo.woowtech.io/odoo",
            serverUrl = serverB,
            deepLink = "/web/login",
        )
        assertTrue(plan is DeepLinkWebPlanner.NavPlan.FullLoad, "warm path-only link must full-load, not be dropped")
        assertTrue((plan as DeepLinkWebPlanner.NavPlan.FullLoad).url.endsWith("/web/login"))
    }

    // ── apply-layer re-validation (parity with iOS deepLinkApplyPlan) ──────────

    @Test
    fun `Given prefix-spoofing host link when plan then null (rejected, no load)`() {
        // "/web@evil.com" merely shares the "/web" prefix; it is NOT the /web path segment and must
        // be rejected at the apply layer rather than concatenated onto the server URL.
        assertNull(
            DeepLinkWebPlanner.plan(
                currentUrl = "https://b-odoo.woowtech.io/odoo",
                serverUrl = serverB,
                deepLink = "/web@evil.com",
            ),
        )
    }

    @Test
    fun `Given path traversal link when plan then null (rejected, no load)`() {
        assertNull(
            DeepLinkWebPlanner.plan(
                currentUrl = "https://b-odoo.woowtech.io/odoo",
                serverUrl = serverB,
                deepLink = "/web/../secret",
            ),
        )
    }
}
