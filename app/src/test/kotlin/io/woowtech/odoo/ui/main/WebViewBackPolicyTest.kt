package io.woowtech.odoo.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D1（2026-09-28 Android 全面實測 A06）：系統返回只在 WebView 有「同一帳號主機、非登入頁」的上一頁時
 * 才交給 WebView.goBack()；其餘交還 NavHost／Activity。見 [OdooWebViewSystemBackTest]。
 *
 * D-R2-2（2026-09-29 第二輪 A06-13..A06-15）：歷史第一格是起始網址（`/web?db=` 被伺服器轉址成
 * `/odoo?db=odoo`），Odoo 路由隨後再 push 一格 Discuss。退到這一格只會重播一次 Inbox，
 * 要多按一次返回才離開 App，所以歷史第一格的起始網址不算「上一頁」。
 */
class WebViewBackPolicyTest {

    private val server = "https://erp.example.invalid"

    @Test
    fun `Given no WebView history when deciding then back is not taken by the WebView`() {
        assertFalse(
            WebViewBackPolicy.canNavigateBack(canGoBack = false, previousUrl = null, previousIndex = -1, serverUrl = server),
        )
    }

    @Test
    fun `Given a same-host previous Odoo page when deciding then the WebView goes back`() {
        assertTrue(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "https://ERP.example.invalid/odoo/contacts",
                previousIndex = 1,
                serverUrl = server,
            ),
        )
    }

    @Test
    fun `Given canGoBack but the previous entry is unknown when deciding then back is not taken`() {
        assertFalse(
            WebViewBackPolicy.canNavigateBack(canGoBack = true, previousUrl = null, previousIndex = 0, serverUrl = server),
        )
    }

    @Test
    fun `Given the previous entry is another host when deciding then back is not taken`() {
        assertFalse(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "https://other.example.invalid/odoo/discuss",
                previousIndex = 1,
                serverUrl = server,
            ),
        )
    }

    @Test
    fun `Given the previous entry is the Odoo login page when deciding then back is not taken`() {
        assertFalse(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "$server/web/login?redirect=%2Fodoo",
                previousIndex = 1,
                serverUrl = server,
            ),
        )
    }

    @Test
    fun `Given the previous entry is the redirected start URL at the history root when deciding then back is not taken`() {
        assertFalse(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "$server/odoo?db=odoo",
                previousIndex = 0,
                serverUrl = server,
            ),
        )
    }

    @Test
    fun `Given the previous entry is the unredirected start URL at the history root when deciding then back is not taken`() {
        assertFalse(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "$server/web?db=odoo",
                previousIndex = 0,
                serverUrl = server,
            ),
        )
    }

    @Test
    fun `Given a real Odoo page at the history root when deciding then the WebView goes back`() {
        assertTrue(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "$server/odoo/contacts",
                previousIndex = 0,
                serverUrl = server,
            ),
        )
    }

    @Test
    fun `Given a hash-routed Odoo page at the history root when deciding then the WebView goes back`() {
        // Odoo <= 17 keeps the path at /web and routes by fragment: that root entry is a real page.
        assertTrue(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "$server/web?db=odoo#action=12&model=res.partner",
                previousIndex = 0,
                serverUrl = server,
            ),
        )
    }
}
