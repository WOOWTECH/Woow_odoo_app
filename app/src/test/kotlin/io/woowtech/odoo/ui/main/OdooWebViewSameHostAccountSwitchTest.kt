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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * pi 複查 P1（PI-REVIEW-0929-ANDROID-IOS-INCREMENTAL）：同一台伺服器（同網址）上的兩個帳號互相切換。
 *
 * 修補前 [OdooWebView] 的 update{} 只在 serverUrl 改變時才重新隔離 cookie 並重載；同網址、不同資料庫／
 * 使用者的 A→B 切換什麼都不做，WebView 繼續用 A 的 session 顯示 A 的頁面。若切換是推播點擊觸發的
 * （MainActivity 先切到 B、再設 B 的 pending 連結），暖啟動分支還會把 B 的連結直接套在 A 的頁面上。
 *
 * 期望：帳號（id／資料庫）一變就跟換主機一樣：先依 B 規劃 cookie、載入 B 的首頁、下一頁清掉歷史，
 * B 的連結要等 B 的頁面載入完才套用。WebView 是 Robolectric ShadowWebView（沒有 Chromium），
 * 以 onPageFinished／pushEntryToHistory 模擬頁面載入與 Odoo 導頁。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class OdooWebViewSameHostAccountSwitchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var webView: WebView

    private var account by mutableStateOf(ACCOUNT_A)
    private var deepLink by mutableStateOf<String?>(null)

    /** Every account the WebView prepared cookies for, in order. */
    private val cookiePlansFor = mutableListOf<String>()

    private fun render() {
        composeRule.setContent {
            OdooWebView(
                accountId = account.id,
                serverUrl = SERVER,
                database = account.database,
                planCookies = { id, _, _ -> cookiePlansFor += id; WebViewCookiePlan.Clear },
                onCookiesApplied = { _, _ -> },
                deepLinkUrl = deepLink,
                onDeepLinkConsumed = { deepLink = null },
                onWebViewCreated = { webView = it },
                onLoadingChanged = {},
                onSelfHeal = { false },
                getFreshSessionId = { null },
                onReloginRequired = {},
            )
        }
        composeRule.waitForIdle()
    }

    private fun lastLoadedUrl(): String? {
        var url: String? = null
        composeRule.runOnUiThread { url = shadowOf(webView).lastLoadedUrl }
        return url
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

    private fun switchTo(target: TestAccount, pendingLink: String? = null) {
        composeRule.runOnUiThread {
            account = target
            deepLink = pendingLink
        }
        composeRule.waitForIdle()
    }

    private fun showAccountAHome() {
        render()
        assertEquals("$SERVER/web?db=${ACCOUNT_A.database}", lastLoadedUrl())
        pageFinished("$SERVER/odoo/discuss")
    }

    @Test
    fun `Given account A on the same server when switching to account B then cookies are isolated for B and B's database is loaded`() {
        showAccountAHome()

        switchTo(ACCOUNT_B)

        assertEquals(listOf(ACCOUNT_A.id, ACCOUNT_B.id), cookiePlansFor)
        assertEquals("$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl())
    }

    @Test
    fun `Given a same-server switch A to B to A then each switch re-isolates cookies and reloads that account`() {
        showAccountAHome()

        switchTo(ACCOUNT_B)
        pageFinished("$SERVER/odoo/discuss")
        switchTo(ACCOUNT_A)

        assertEquals(listOf(ACCOUNT_A.id, ACCOUNT_B.id, ACCOUNT_A.id), cookiePlansFor)
        assertEquals("$SERVER/web?db=${ACCOUNT_A.database}", lastLoadedUrl())
    }

    @Test
    fun `Given a push tap for account B while A is shown then B's link waits for B's page instead of loading on A's session`() {
        showAccountAHome()

        // MainActivity switches to B, then primes B's pending link: both arrive in one recomposition.
        switchTo(ACCOUNT_B, pendingLink = "/web#action=calendar.action_calendar_event")

        assertEquals("the switch loads B's home first", "$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl())
        pageFinished("$SERVER/odoo/discuss")
        assertEquals("$SERVER/web#action=calendar.action_calendar_event", lastLoadedUrl())
    }

    @Test
    fun `Given A had Odoo history when switching to B on the same server then back can never return to A's pages`() {
        showAccountAHome()
        odooNavigatesTo("$SERVER/odoo/contacts")
        odooNavigatesTo("$SERVER/odoo/contacts/7")

        switchTo(ACCOUNT_B)
        pageFinished("$SERVER/odoo/discuss")

        var intercepted = true
        composeRule.runOnUiThread {
            intercepted = composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks()
        }
        assertFalse("B's first page must not step back into A's history", intercepted)
    }

    private data class TestAccount(val id: String, val database: String)

    private companion object {
        const val SERVER = "https://odoo.example.com"
        val ACCOUNT_A = TestAccount(id = "account-a", database = "db_a")
        val ACCOUNT_B = TestAccount(id = "account-b", database = "db_b")
    }
}
