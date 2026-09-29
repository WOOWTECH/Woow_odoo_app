package io.woowtech.odoo.data.push

import io.woowtech.odoo.ui.main.DeepLinkWebPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 推播點擊路徑回歸（擁有者 2026-09-29 核准加嚴到與 iOS 一致，OWNER-APPROVAL-PUSH-LINK-20260929）。
 *
 * 推播點擊送進 MainActivity 的 intent 帶 [NotificationHelper.EXTRA_ACTION_URL]（`odoo_action_url`）與
 * [NotificationHelper.EXTRA_TENANT_ID]（`odoo_tenant_id`）。MainActivity 交給 [DeepLinkRouter.route]
 * 決定帳號並驗證連結 → [DeepLinkManager] 綁帳號暫存 → 該帳號頁面載入完成（App Lock 解鎖後才會載入）
 * 才由 [DeepLinkWebPlanner.plan] 再驗一次並決定 WebView 載入的網址。舊外掛 payload（沒有 tenant id）
 * 走 [DeepLinkRoute.ApplyToActive]，最後同樣經過 [DeepLinkWebPlanner.plan]。
 *
 * 這裡驗的是「點擊後 WebView 會載入什麼」；不是真推播 E2E（FCM 送達與系統通知列不在此）。
 * 純 JVM 測試，woowtech／apporo 兩個 unit test task 都會跑。
 */
class PushTapLinkValidationTest {

    private val serverUrl = "https://www.apporo.ai"
    private val tenant = "tenant-apporo"
    private val account = RoutableAccount(id = "acc-apporo", tenantId = tenant, serverHost = "www.apporo.ai")
    private val deepLinkManager = DeepLinkManager()

    /** Simulates the tap (tenant payload) and returns what the WebView would load, or null. */
    private fun tap(actionUrl: String, tenantId: String? = tenant, loggedIn: Boolean = true): String? {
        val route = DeepLinkRouter.route(
            tenantId = tenantId,
            actionUrl = actionUrl,
            accounts = listOf(account),
            isLoggedIn = { loggedIn },
        )
        val url = when (route) {
            is DeepLinkRoute.SwitchAndApply -> route.url
            is DeepLinkRoute.ApplyToActive -> route.url
            is DeepLinkRoute.Drop -> return null
        }
        deepLinkManager.setPending(url = url, accountId = account.id)
        return pageLoaded()
    }

    /** The account's page finished loading (after any App Lock unlock): the apply flow runs. */
    private fun pageLoaded(): String? {
        val pending = deepLinkManager.consumeFor(account.id) ?: return null
        val plan = DeepLinkWebPlanner.plan(currentUrl = null, serverUrl = serverUrl, deepLink = pending)
        return (plan as? DeepLinkWebPlanner.NavPlan.FullLoad)?.url
    }

    @Test
    fun `Given a relative web path when the push is tapped then the WebView opens it on the account server`() {
        assertEquals(
            "https://www.apporo.ai/web#action=calendar.action_calendar_event",
            tap("/web#action=calendar.action_calendar_event"),
        )
    }

    @Test
    fun `Given a same-server https URL when the push is tapped then the WebView opens that URL`() {
        assertEquals(
            "https://www.apporo.ai/web#id=7&model=res.partner&view_type=form",
            tap("https://www.apporo.ai/web#id=7&model=res.partner&view_type=form"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "http://www.apporo.ai/web#action=calendar.action_calendar_event",
        "https://evil.example.com/web",
        "intent://www.apporo.ai/web#Intent;scheme=https;end",
        "javascript:alert(1)",
        "data:text/html,<h1>x</h1>",
        "/web#id=1\u0000",
        "/web#id=1‮",
        "/web/../web",
        "/web/%2e%2e/web",
        "/web/%2E%2E/web",
        "/odoo/calendar",
        "/website/info",
    ])
    fun `Given a rejected link when the push is tapped then the router drops it and nothing is queued`(url: String) {
        val route = DeepLinkRouter.route(
            tenantId = tenant,
            actionUrl = url,
            accounts = listOf(account),
            isLoggedIn = { true },
        )

        assertInstanceOf(DeepLinkRoute.Drop::class.java, route)
        assertNull(tap(url))
        assertNull(deepLinkManager.pending.value)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "http://www.apporo.ai/web",
        "/web/%2e%2e/web",
        "/web/%2E%2E/web",
        "/web#id=1‮",
    ])
    fun `Given an old-plugin payload without tenant id when a rejected link is applied then the WebView loads nothing`(url: String) {
        assertNull(tap(url, tenantId = null))
    }

    @Test
    fun `Given the target account is not signed in when the push is tapped then nothing is navigated`() {
        assertNull(tap("/web#action=calendar.action_calendar_event", loggedIn = false))
        assertNull(deepLinkManager.pending.value)
    }

    @Test
    fun `Given App Lock is showing when the push is tapped then the link waits and opens only after unlock`() {
        val route = DeepLinkRouter.route(
            tenantId = tenant,
            actionUrl = "/web#action=calendar.action_calendar_event",
            accounts = listOf(account),
            isLoggedIn = { true },
        ) as DeepLinkRoute.SwitchAndApply
        val tappedAt = 1_000_000L
        deepLinkManager.setPending(url = route.url, accountId = route.accountId, nowMillis = tappedAt)

        // Locked: no WebView page loads, so nothing consumes the link; it stays queued.
        assertEquals(route.url, deepLinkManager.pending.value?.url)

        // Unlocked 30 s later: the account page loads and the link is applied exactly once.
        val applied = deepLinkManager.consumeFor(account.id, nowMillis = tappedAt + 30_000L)
        assertEquals("/web#action=calendar.action_calendar_event", applied)
        assertNull(deepLinkManager.consumeFor(account.id, nowMillis = tappedAt + 31_000L))
    }
}
