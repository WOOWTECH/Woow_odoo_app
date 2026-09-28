package io.woowtech.odoo.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D1（2026-09-28 Android 全面實測 A06）：系統返回只在 WebView 有「同一帳號主機、非登入頁」的上一頁時
 * 才交給 WebView.goBack()；其餘交還 NavHost／Activity。見 [OdooWebViewSystemBackTest]。
 */
class WebViewBackPolicyTest {

    private val server = "https://erp.example.invalid"

    @Test
    fun `Given no WebView history when deciding then back is not taken by the WebView`() {
        assertFalse(WebViewBackPolicy.canNavigateBack(canGoBack = false, previousUrl = null, serverUrl = server))
    }

    @Test
    fun `Given a same-host previous Odoo page when deciding then the WebView goes back`() {
        assertTrue(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "https://ERP.example.invalid/odoo/contacts",
                serverUrl = server,
            ),
        )
    }

    @Test
    fun `Given canGoBack but the previous entry is unknown when deciding then back is not taken`() {
        assertFalse(WebViewBackPolicy.canNavigateBack(canGoBack = true, previousUrl = null, serverUrl = server))
    }

    @Test
    fun `Given the previous entry is another host when deciding then back is not taken`() {
        assertFalse(
            WebViewBackPolicy.canNavigateBack(
                canGoBack = true,
                previousUrl = "https://other.example.invalid/odoo/discuss",
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
                serverUrl = server,
            ),
        )
    }
}
