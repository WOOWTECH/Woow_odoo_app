package io.woowtech.odoo.ui.main

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W2-4 L1（2026-10-08 Pixel 7a vc5 實機）：斷網冷啟動顯示 Chromium「Webpage not available …
 * net::ERR_NAME_NOT_RESOLVED」並露出伺服器網址、沒有重試。改為：主框架連線錯誤 → App 自己的離線畫面
 * （不含錯誤碼與網址）、重試重新載入原網址；子資源錯誤不算；舊帳號 WebView 的錯誤不影響目前畫面。
 *
 * WebView 為 Robolectric ShadowWebView（沒有 Chromium）：直接呼叫 webViewClient 回呼模擬錯誤與載入完成。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class OdooWebViewOfflineTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var webView: WebView
    private var accountId by mutableStateOf("account-a")

    private fun render() {
        composeRule.setContent {
            OdooWebView(
                accountId = accountId,
                serverUrl = SERVER,
                database = "demo_db",
                planCookies = { _, _, _ -> WebViewCookiePlan.Clear },
                onCookiesApplied = { _, _ -> },
                onWebViewCreated = { webView = it },
                onLoadingChanged = {},
                onSelfHeal = { false },
                getFreshSessionId = { null },
                onReloginRequired = {},
            )
        }
        composeRule.waitForIdle()
    }

    private fun request(url: String, mainFrame: Boolean): WebResourceRequest = mockk {
        every { isForMainFrame } returns mainFrame
        every { this@mockk.url } returns Uri.parse(url)
    }

    private fun error(code: Int): WebResourceError = mockk {
        every { errorCode } returns code
        every { description } returns "net::ERR_NAME_NOT_RESOLVED"
    }

    private fun failLoad(view: WebView, url: String, mainFrame: Boolean = true, code: Int = WebViewClient.ERROR_HOST_LOOKUP) {
        composeRule.runOnUiThread {
            val client = shadowOf(view).webViewClient
            client.onPageStarted(view, url, null)
            client.onReceivedError(view, request(url, mainFrame), error(code))
            client.onPageFinished(view, url)
        }
        composeRule.waitForIdle()
    }

    private fun pageFinished(url: String) {
        composeRule.runOnUiThread { shadowOf(webView).webViewClient.onPageFinished(webView, url) }
        composeRule.waitForIdle()
    }

    @Test
    fun `Given the main frame cannot reach the server then the offline screen shows without the address`() {
        render()
        failLoad(webView, "$SERVER/web?db=demo_db")

        composeRule.onNodeWithTag(WEBVIEW_OFFLINE_TAG).assertExists()
        composeRule.onNodeWithText("Can't connect to the server").assertExists()
        composeRule.onNodeWithText("Retry").assertExists()
        composeRule.onNodeWithText("erp-a.example.invalid", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("ERR_", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `Given Traditional Chinese when offline then the screen is localized`() {
        render()
        failLoad(webView, "$SERVER/web?db=demo_db")

        composeRule.onNodeWithText("無法連線到伺服器").assertExists()
        composeRule.onNodeWithText("重試").assertExists()
    }

    @Test
    fun `Given offline when Retry is tapped then the failed page reloads and a real page ends offline`() {
        render()
        val failed = "$SERVER/odoo/discuss"
        failLoad(webView, failed)

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()
        assertEquals(failed, shadowOf(webView).lastLoadedUrl)
        composeRule.onNodeWithTag(WEBVIEW_OFFLINE_TAG).assertExists()

        pageFinished(failed)
        composeRule.onNodeWithTag(WEBVIEW_OFFLINE_TAG).assertDoesNotExist()
    }

    @Test
    fun `Given a subresource fails then no offline screen`() {
        render()
        failLoad(webView, "$SERVER/web/image/42", mainFrame = false)

        composeRule.onNodeWithTag(WEBVIEW_OFFLINE_TAG).assertDoesNotExist()
    }

    @Test
    fun `Given the account was switched when the replaced WebView reports an error then the screen stays clear`() {
        render()
        val replaced = webView
        accountId = "account-b"
        composeRule.waitForIdle()
        assertNotSame(replaced, webView)

        composeRule.runOnUiThread {
            // The replaced instance's client was cut on release; even its original client must be inert.
            shadowOf(webView).webViewClient.onReceivedError(
                replaced, request("$SERVER/web?db=demo_db", true), error(WebViewClient.ERROR_HOST_LOOKUP),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WEBVIEW_OFFLINE_TAG).assertDoesNotExist()

        failLoad(webView, "$SERVER/web?db=demo_db")
        composeRule.onNodeWithTag(WEBVIEW_OFFLINE_TAG).assertExists()
    }

    private companion object {
        const val SERVER = "https://erp-a.example.invalid"
    }
}
