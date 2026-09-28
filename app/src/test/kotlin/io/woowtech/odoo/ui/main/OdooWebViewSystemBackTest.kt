package io.woowtech.odoo.ui.main

import android.app.Application
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線 D1（2026-09-28 Android 全面實測 A06-01..A06-12）：在主畫面（Odoo WebView）按系統返回鍵，
 * 不會回到 Odoo 上一頁，而是直接關閉 App（A06-09-back.logcat.txt：Transition CLOSE）；重開後回到
 * Discuss Inbox，使用者原本的位置遺失。
 *
 * 根因：整個 App 只有 LoginScreen／SettingsScreen 有 BackHandler；[OdooWebView] 沒有把系統返回
 * 接到 WebView 的 canGoBack()/goBack()，返回鍵直接落到 NavHost／Activity（Main 是根畫面 → 離開）。
 *
 * 期望（與瀏覽器一致）：
 *  - WebView 有同一帳號主機的上一頁 → 系統返回 = WebView.goBack()，App 不離開
 *  - 沒有上一頁（Odoo 第一頁）→ 不攔截，交還 NavHost／Activity（照舊離開）
 *  - 上一頁是 /web/login 或別的主機（例如切換帳號前的舊帳號頁）→ 不攔截，絕不帶回舊帳號頁
 *
 * WebView 為 Robolectric ShadowWebView（沒有 Chromium）：以 pushEntryToHistory + 呼叫
 * doUpdateVisitedHistory／onPageFinished 模擬 Odoo 的 history.pushState 與頁面載入。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class OdooWebViewSystemBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var webView: WebView

    private var serverUrl by mutableStateOf(SERVER_A)

    private fun render() {
        composeRule.setContent {
            OdooWebView(
                accountId = if (serverUrl == SERVER_A) "account-a" else "account-b",
                serverUrl = serverUrl,
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

    /** Simulates Chromium finishing a document load of [url]. */
    private fun pageFinished(url: String) {
        composeRule.runOnUiThread { shadowOf(webView).webViewClient.onPageFinished(webView, url) }
        composeRule.waitForIdle()
    }

    /** Simulates an Odoo client-side navigation (history.pushState) to [url]. */
    private fun odooNavigatesTo(url: String) {
        composeRule.runOnUiThread {
            shadowOf(webView).pushEntryToHistory(url)
            shadowOf(webView).webViewClient.doUpdateVisitedHistory(webView, url, false)
        }
        composeRule.waitForIdle()
    }

    private fun backIsIntercepted(): Boolean {
        var enabled = false
        composeRule.runOnUiThread {
            enabled = composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks()
        }
        return enabled
    }

    private fun pressSystemBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun loadFirstOdooPage(server: String = SERVER_A) {
        composeRule.runOnUiThread { webView.clearHistory() }
        odooNavigatesTo("$server/odoo/discuss")
        pageFinished("$server/odoo/discuss")
    }

    @Test
    fun `Given Odoo navigated from Discuss to a contact form when system back is pressed then the WebView goes back and the app stays open`() {
        render()
        loadFirstOdooPage()
        odooNavigatesTo("$SERVER_A/odoo/contacts")
        odooNavigatesTo("$SERVER_A/odoo/contacts/7")

        assertTrue("an Odoo page with history must intercept system back", backIsIntercepted())
        pressSystemBack()

        assertEquals(1, shadowOf(webView).goBackInvocations)
        assertFalse("system back must not close the app", composeRule.activity.isFinishing)
    }

    @Test
    fun `Given only the first Odoo page when system back is pressed then OdooWebView leaves it to the app (exit)`() {
        render()
        loadFirstOdooPage()

        assertFalse("no Odoo history: system back belongs to NavHost/Activity", backIsIntercepted())
    }

    /**
     * D-R2-2（2026-09-29 第二輪 A06-13..A06-15）：真實的第一頁在歷史裡是兩格——`/web?db=` 被伺服器轉址成
     * `/odoo?db=…`（第一格），Odoo 路由再 push Discuss（第二格）。舊版把第一格當成可返回的上一頁，
     * Inbox 連續出現兩次、要多按一次返回才離開 App。
     */
    private fun loadFirstOdooPageAsTheRealWebViewRecordsIt(server: String = SERVER_A) {
        composeRule.runOnUiThread { webView.clearHistory() }
        odooNavigatesTo("$server/odoo?db=demo_db")
        pageFinished("$server/odoo?db=demo_db")
        odooNavigatesTo("$server/odoo/discuss")
    }

    @Test
    fun `Given the start URL entry sits behind Discuss when system back is pressed on Discuss then OdooWebView leaves it to the app (exit)`() {
        render()
        loadFirstOdooPageAsTheRealWebViewRecordsIt()

        assertFalse("the start URL entry only replays Inbox: back must exit", backIsIntercepted())
    }

    @Test
    fun `Given Odoo navigated from Discuss to Contacts when system back returns to Discuss then the next back exits`() {
        render()
        loadFirstOdooPageAsTheRealWebViewRecordsIt()
        odooNavigatesTo("$SERVER_A/odoo/contacts")

        assertTrue("Contacts -> Discuss is a real back step", backIsIntercepted())
        pressSystemBack()
        assertEquals(1, shadowOf(webView).goBackInvocations)

        assertFalse("back on Discuss (start URL behind it) must exit", backIsIntercepted())
    }

    @Test
    fun `Given the previous history entry is the Odoo login page when system back is pressed then it is not intercepted`() {
        render()
        composeRule.runOnUiThread { webView.clearHistory() }
        odooNavigatesTo("$SERVER_A/web/login")
        odooNavigatesTo("$SERVER_A/odoo/discuss")
        pageFinished("$SERVER_A/odoo/discuss")

        assertFalse("back must never return to /web/login", backIsIntercepted())
    }

    @Test
    fun `Given the account was switched when the new account page loaded then back never returns to the previous account's pages`() {
        render()
        loadFirstOdooPage()
        odooNavigatesTo("$SERVER_A/odoo/contacts/7")
        assertTrue(backIsIntercepted())

        serverUrl = SERVER_B
        composeRule.waitForIdle()
        // The switch reload lands on account B; A's pages (and B's own boot URL) are still behind it
        // in the WebView history, so without clearing it back would step through them.
        odooNavigatesTo("$SERVER_B/web?db=demo_db")
        odooNavigatesTo("$SERVER_B/odoo/discuss")
        pageFinished("$SERVER_B/odoo/discuss")

        assertFalse("previous account's pages must not be reachable via back", backIsIntercepted())
        assertEquals(0, shadowOf(webView).goBackInvocations)
    }

    private companion object {
        const val SERVER_A = "https://erp-a.example.invalid"
        const val SERVER_B = "https://erp-b.example.invalid"
    }
}
