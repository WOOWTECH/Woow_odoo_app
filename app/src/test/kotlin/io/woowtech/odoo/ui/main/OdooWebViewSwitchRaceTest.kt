package io.woowtech.odoo.ui.main

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * pi 第二次回歸複查（PI-REVIEW-0929-FIX-RECHECK-2）的三個 P1 交錯反例，全部是「切換帳號後，上一個帳號
 * 的非同步事件晚到」：
 *
 *  1. A 的舊頁面在 B 的載入送出後，依序送來 onPageStarted＋onPageFinished——只看「有沒有 start」的閘門
 *     分不出這是 A 還是 B 的頁面，會把 B 的連結提早套用。
 *  2. B→C 快速切換時，B 的 cookie 清除回呼晚於 C 到達——舊回呼仍能寫入 B 的 cookie、記錄 B 為 owner。
 *  3. A 的 self-heal（背景重新登入）在切到 B 之後才完成——仍會寫入 A 的 cookie、載入 A 的首頁或導去重新
 *     登入；冷啟動 A 的 cookie 回呼在中途切換後才到，也仍會載入 A。
 *
 * 期望：每個帳號目標一個 WebView 實例（切換＝建立新實例、銷毀舊實例），所有回呼先確認事件來自目前實例；
 * 對全域 CookieManager 的每個副作用（寫 cookie、flush、記 owner、載入頁面、導去重新登入）都要先確認
 * 發起它的切換仍是目前的切換；cookie 操作一次只跑一組，舊回呼不可能與新的交錯。
 *
 * WebView 是 Robolectric ShadowWebView（沒有 Chromium）；以直接呼叫舊實例的 WebViewClient 模擬晚到事件。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class OdooWebViewSwitchRaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The most recently created WebView instance (the one on screen). */
    private lateinit var webView: WebView

    private var account by mutableStateOf(ACCOUNT_A)
    private var deepLink by mutableStateOf<String?>(null)

    private val cookiePlans = mutableMapOf<String, WebViewCookiePlan>()

    /** Accounts recorded as the WebView cookie owner, in order. */
    private val ownerRecords = mutableListOf<String>()

    private var heal: CompletableDeferred<Boolean>? = null
    private var healCalls = 0
    private var reloginCalls = 0
    private val cookieStore = OutOfOrderCookieStore()

    private fun render() {
        composeRule.setContent {
            OdooWebView(
                accountId = account.id,
                serverUrl = SERVER,
                database = account.database,
                planCookies = { id, _, _ -> cookiePlans[id] ?: WebViewCookiePlan.Clear },
                onCookiesApplied = { id, _ -> ownerRecords += id },
                deepLinkUrl = deepLink,
                onDeepLinkConsumed = { deepLink = null },
                onWebViewCreated = { webView = it },
                onLoadingChanged = {},
                onSelfHeal = { healCalls++; heal?.await() ?: false },
                getFreshSessionId = { "sid-a-fresh" },
                onReloginRequired = { reloginCalls++ },
                cookieStore = cookieStore,
            )
        }
        idle()
    }

    private fun idle() {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun lastLoadedUrl(view: WebView = webView): String? {
        var url: String? = null
        composeRule.runOnUiThread { url = shadowOf(view).lastLoadedUrl }
        return url
    }

    private fun historySize(view: WebView = webView): Int {
        var size = 0
        composeRule.runOnUiThread { size = view.copyBackForwardList().size }
        return size
    }

    private fun clientOf(view: WebView): WebViewClient {
        var client: WebViewClient? = null
        composeRule.runOnUiThread { client = shadowOf(view).webViewClient }
        return client!!
    }

    /**
     * Delivers onPageStarted to [client] (default: [view]'s current client). Chromium may already have
     * dispatched events of a replaced instance to the client it had then — pass that captured client.
     */
    private fun pageStarted(view: WebView, url: String, client: WebViewClient = clientOf(view)) {
        composeRule.runOnUiThread { client.onPageStarted(view, url, null) }
        idle()
    }

    private fun pageFinished(view: WebView, url: String, client: WebViewClient = clientOf(view)) {
        composeRule.runOnUiThread { client.onPageFinished(view, url) }
        idle()
    }

    private fun pageLoads(view: WebView, url: String, client: WebViewClient = clientOf(view)) {
        pageStarted(view, url, client)
        pageFinished(view, url, client)
    }

    private fun odooNavigatesTo(view: WebView, url: String) {
        composeRule.runOnUiThread {
            shadowOf(view).pushEntryToHistory(url)
            shadowOf(view).webViewClient.doUpdateVisitedHistory(view, url, false)
        }
        idle()
    }

    /** Odoo redirects [view] to its login page (expired session): returns whether the load was cancelled. */
    private fun loginRedirect(view: WebView, client: WebViewClient = clientOf(view)): Boolean {
        var cancelled = false
        composeRule.runOnUiThread {
            cancelled = client.shouldOverrideUrlLoading(view, request("$SERVER/web/login"))
        }
        idle()
        return cancelled
    }

    private fun switchTo(target: TestAccount, pendingLink: String? = null) {
        composeRule.runOnUiThread {
            account = target
            deepLink = pendingLink
        }
        idle()
    }

    private fun showAccountAHome(): WebView {
        render()
        assertEquals("$SERVER/web?db=${ACCOUNT_A.database}", lastLoadedUrl())
        pageLoads(webView, "$SERVER/odoo/discuss")
        return webView
    }

    private fun completeRemovalsLastFirst() {
        composeRule.runOnUiThread { cookieStore.completeAllLastFirst() }
        idle()
    }

    // --- 1. A's late onPageStarted + onPageFinished after the switch load was issued -----------------

    @Test
    fun `Given a same-server switch to B when A's page reports a late start and finish then B's link waits and B's page is not considered loaded`() {
        val instanceA = showAccountAHome()
        odooNavigatesTo(instanceA, "$SERVER/odoo/contacts")
        odooNavigatesTo(instanceA, "$SERVER/odoo/contacts/7")
        val clientA = clientOf(instanceA)

        switchTo(ACCOUNT_B, pendingLink = LINK)
        assertEquals("$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl())
        val historyBefore = historySize()

        // A's already-queued navigation events arrive after B's load was handed to the WebView, through
        // the client A's page was using.
        pageLoads(instanceA, "$SERVER/odoo/contacts/7", clientA)

        assertEquals("A's late events must not apply B's link", "$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl())
        assertNotNull("B's link is still pending", deepLink)
        assertEquals("A's late events must not clear B's history", historyBefore, historySize())

        // A warm recomposition must not treat B's page as loaded either.
        composeRule.runOnUiThread { deepLink = LINK_2 }
        idle()
        assertEquals("$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl())

        pageLoads(webView, "$SERVER/odoo/discuss")
        assertEquals("$SERVER$LINK_2", lastLoadedUrl())
        assertNull(deepLink)
    }

    @Test
    fun `Given an account switch then the previous account's WebView instance is replaced and destroyed`() {
        val instanceA = showAccountAHome()
        val clientA = clientOf(instanceA)

        switchTo(ACCOUNT_B)

        assertNotSame("each account target gets its own WebView instance", instanceA, webView)
        assertTrue("the previous account's WebView is destroyed", shadowOf(instanceA).wasDestroyCalled())
        assertTrue("its app callbacks are detached", shadowOf(instanceA).webViewClient !== clientA)
        assertEquals("the new instance only ever loads B", "$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl(webView))
    }

    @Test
    fun `Given the account did not change when recomposing with a new deep link then the same WebView instance is kept`() {
        val instanceA = showAccountAHome()

        composeRule.runOnUiThread { deepLink = LINK }
        idle()

        assertTrue(instanceA === webView)
        assertFalse(shadowOf(instanceA).wasDestroyCalled())
        assertEquals("$SERVER$LINK", lastLoadedUrl())
    }

    @Test
    fun `Given a switch to B when A's WebView later redirects to the login page then no self-heal runs for A and the load is blocked`() {
        val instanceA = showAccountAHome()
        val clientA = clientOf(instanceA)

        switchTo(ACCOUNT_B)
        val cancelled = loginRedirect(instanceA, clientA)

        assertTrue("a stale instance may not navigate", cancelled)
        assertEquals(0, healCalls)
        assertEquals(0, reloginCalls)
    }

    // --- 2. Reordered cookie removal callbacks during B -> C ------------------------------------------

    @Test
    fun `Given a fast B to C switch when B's cookie removal completes last then C's session stays and B is never recorded or loaded`() {
        showAccountAHome()
        cookiePlans[ACCOUNT_B.id] = WebViewCookiePlan.Replace("sid-b")
        cookiePlans[ACCOUNT_C.id] = WebViewCookiePlan.Replace("sid-c")
        cookieStore.deferRemoval = true
        ownerRecords.clear()

        switchTo(ACCOUNT_B)
        switchTo(ACCOUNT_C)
        completeRemovalsLastFirst()

        assertTrue("cookie operations never interleave", cookieStore.maxOutstandingRemovals <= 1)
        assertEquals("the last session written is C's", "session_id=sid-c", cookieStore.sessionWrites.last())
        assertFalse("a superseded switch writes no cookie", cookieStore.sessionWrites.contains("session_id=sid-b"))
        assertEquals("only C is recorded as the cookie owner", listOf(ACCOUNT_C.id), ownerRecords)
        assertEquals("$SERVER/web?db=${ACCOUNT_C.database}", lastLoadedUrl())
    }

    // --- 3. Self-heal and cold-start continuations that complete after a switch -----------------------

    @Test
    fun `Given A's self-heal is in flight when switching to B and the heal succeeds then nothing is written or loaded for A`() {
        val instanceA = showAccountAHome()
        heal = CompletableDeferred()
        assertTrue(loginRedirect(instanceA))
        assertEquals(1, healCalls)

        switchTo(ACCOUNT_B)
        ownerRecords.clear()
        val writesBefore = cookieStore.sessionWrites.size
        composeRule.runOnUiThread { heal!!.complete(true) }
        idle()

        assertEquals("no A cookie after the switch", writesBefore, cookieStore.sessionWrites.size)
        assertTrue("A is not recorded as owner", ownerRecords.isEmpty())
        assertEquals("$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl())
        assertEquals("A's old instance loads nothing more", "$SERVER/web?db=${ACCOUNT_A.database}", lastLoadedUrl(instanceA))
        assertEquals(0, reloginCalls)
    }

    @Test
    fun `Given A's self-heal is in flight when switching to B and the heal fails then B is not sent to re-login`() {
        val instanceA = showAccountAHome()
        heal = CompletableDeferred()
        assertTrue(loginRedirect(instanceA))

        switchTo(ACCOUNT_B)
        composeRule.runOnUiThread { heal!!.complete(false) }
        idle()

        assertEquals("A's failed heal must not redirect B's screen", 0, reloginCalls)
    }

    @Test
    fun `Given A's cold-start cookie removal is still running when switching to B then A's page never loads and B loads after its own cookies`() {
        cookiePlans[ACCOUNT_A.id] = WebViewCookiePlan.Replace("sid-a")
        cookiePlans[ACCOUNT_B.id] = WebViewCookiePlan.Replace("sid-b")
        cookieStore.deferRemoval = true
        render()
        val instanceA = webView
        assertNull("nothing loads before the removal completed", lastLoadedUrl(instanceA))

        switchTo(ACCOUNT_B)
        completeRemovalsLastFirst()

        assertNull("A's cold start never loads after the switch", lastLoadedUrl(instanceA))
        assertEquals(listOf(ACCOUNT_B.id), ownerRecords)
        assertFalse(cookieStore.sessionWrites.contains("session_id=sid-a"))
        assertEquals("session_id=sid-b", cookieStore.sessionWrites.last())
        assertEquals("$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl())
    }

    private fun request(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = true
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    /**
     * Cookie store whose removals can be held and then completed newest-first — the reordering pi
     * described. Tracks how many removals were outstanding at once.
     */
    private class OutOfOrderCookieStore : WebViewCookieStore {
        var deferRemoval = false
        val sessionWrites = mutableListOf<String>()
        var maxOutstandingRemovals = 0
        private val pending = mutableListOf<() -> Unit>()

        override fun getCookie(url: String): String? = null

        override fun removeAllCookies(onDone: () -> Unit) {
            if (!deferRemoval) {
                onDone()
                return
            }
            pending += onDone
            maxOutstandingRemovals = maxOf(maxOutstandingRemovals, pending.size)
        }

        override fun setCookie(url: String, value: String) {
            sessionWrites += value.substringBefore(';')
        }

        override fun flush() = Unit

        /** Completes every held removal, newest first, including removals issued while completing. */
        fun completeAllLastFirst() {
            while (pending.isNotEmpty()) {
                pending.removeAt(pending.lastIndex).invoke()
            }
        }
    }

    private data class TestAccount(val id: String, val database: String)

    private companion object {
        const val SERVER = "https://odoo.example.com"
        val ACCOUNT_A = TestAccount(id = "account-a", database = "db_a")
        val ACCOUNT_B = TestAccount(id = "account-b", database = "db_b")
        val ACCOUNT_C = TestAccount(id = "account-c", database = "db_c")
        const val LINK = "/web#action=calendar.action_calendar_event"
        const val LINK_2 = "/web#action=contacts.action_contacts"
    }
}
