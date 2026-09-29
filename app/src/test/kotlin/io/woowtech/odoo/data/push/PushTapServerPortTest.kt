package io.woowtech.odoo.data.push

import io.woowtech.odoo.domain.model.OdooAccount
import io.woowtech.odoo.ui.main.DeepLinkWebPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * pi 複查 P2（PI-REVIEW-0929-ANDROID-IOS-INCREMENTAL）：伺服器網址帶非預設埠（例如 `:8443`）時，推播點擊的
 * 同站 https 連結被誤拒。
 *
 * 根因：MainActivity 用字串切割取「主機」（`example.com:8443`，含埠），[DeepLinkValidator] 卻用
 * `URI.host`（`example.com`）比對，兩邊永遠不相等。iOS 的 `OdooAccount.serverHost` 是
 * `URL(fullServerUrl).host`，只取主機名。修補後 Android 也由 [OdooAccount.serverHost] 以 URI 解析主機名，
 * 新舊 payload（有／沒有 tenant id）都用它；比對規則與 iOS 相同：只比主機名、不比埠。
 */
class PushTapServerPortTest {

    private val account = OdooAccount(
        id = "acc-port",
        serverUrl = "https://odoo.example.com:8443",
        database = "odoo",
        username = "admin",
        displayName = "Admin",
        isActive = true,
        tenantId = "tenant-port",
    )
    private val deepLinkManager = DeepLinkManager()

    /** New-payload tap (tenant id) as MainActivity builds it, then the load-gated apply. */
    private fun tapWithTenant(actionUrl: String): String? {
        val route = DeepLinkRouter.route(
            tenantId = account.tenantId,
            actionUrl = actionUrl,
            accounts = listOf(RoutableAccount(id = account.id, tenantId = account.tenantId, serverHost = account.serverHost)),
            isLoggedIn = { true },
        )
        val url = (route as? DeepLinkRoute.SwitchAndApply)?.url ?: return null
        deepLinkManager.setPending(url = url, accountId = account.id)
        return pageLoaded()
    }

    /** Old-payload tap (no tenant id): MainActivity validates against the active account's host. */
    private fun tapWithoutTenant(actionUrl: String): String? {
        val route = DeepLinkRouter.route(tenantId = null, actionUrl = actionUrl, accounts = emptyList(), isLoggedIn = { true })
        val url = (route as DeepLinkRoute.ApplyToActive).url
        if (!DeepLinkValidator.isValid(url = url, serverHost = account.serverHost)) return null
        deepLinkManager.setPending(url = url, accountId = account.id)
        return pageLoaded()
    }

    private fun pageLoaded(): String? {
        val pending = deepLinkManager.consumeFor(account.id) ?: return null
        val plan = DeepLinkWebPlanner.plan(currentUrl = null, serverUrl = account.fullServerUrl, deepLink = pending)
        return (plan as? DeepLinkWebPlanner.NavPlan.FullLoad)?.url
    }

    @Test
    fun `Given a server URL with a port then serverHost is the bare hostname like iOS`() {
        assertEquals("odoo.example.com", account.serverHost)
        assertEquals("odoo.example.com", account.copy(serverUrl = "odoo.example.com:8443").serverHost)
        assertEquals("odoo.example.com", account.copy(serverUrl = "https://odoo.example.com/").serverHost)
    }

    @Test
    fun `Given a server with a port when a new-payload push carries the same-server https URL then the WebView opens it`() {
        assertEquals(
            "https://odoo.example.com:8443/web#id=7&model=res.partner&view_type=form",
            tapWithTenant("https://odoo.example.com:8443/web#id=7&model=res.partner&view_type=form"),
        )
    }

    @Test
    fun `Given a server with a port when an old-payload push carries the same-server https URL then the WebView opens it`() {
        assertEquals(
            "https://odoo.example.com:8443/web#action=calendar.action_calendar_event",
            tapWithoutTenant("https://odoo.example.com:8443/web#action=calendar.action_calendar_event"),
        )
    }

    @Test
    fun `Given a server with a port when the push carries a relative web path then it opens on that server and port`() {
        assertEquals("https://odoo.example.com:8443/web#action=1", tapWithTenant("/web#action=1"))
        assertEquals("https://odoo.example.com:8443/web#action=1", tapWithoutTenant("/web#action=1"))
    }

    @Test
    fun `Given a server with a port when the push carries another host on the same port then it is still rejected`() {
        assertNull(tapWithTenant("https://evil.example.net:8443/web"))
        assertNull(tapWithoutTenant("https://evil.example.net:8443/web"))
        assertNull(tapWithTenant("https://odoo.example.com.evil.net:8443/web"))
    }

    @Test
    fun `Given a server with a port when the push carries http or traversal on the same host then it is still rejected`() {
        assertNull(tapWithTenant("http://odoo.example.com:8443/web"))
        assertNull(tapWithoutTenant("https://odoo.example.com:8443/web/../../etc"))
        assertNull(tapWithoutTenant("javascript:alert(1)"))
    }

    @Test
    fun `iOS parity - the comparison is by hostname only, so the same host on another port is accepted`() {
        // iOS DeepLinkValidator compares `URL.host` with `account.serverHost` (= URL(fullServerUrl).host):
        // the port is not part of either side. Android matches it exactly — never looser, never stricter.
        assertTrue(DeepLinkValidator.isValid(url = "https://odoo.example.com:9443/web", serverHost = account.serverHost))
    }
}
