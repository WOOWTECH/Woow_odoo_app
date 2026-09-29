package io.woowtech.odoo.ui.main

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * pi 第三次回歸複查（PI-REVIEW-0929-FIX-RECHECK-3）的 P1：cookie 序列器與目標代次原本只存在「單一次」
 * OdooWebView 組合裡。整個 Main 畫面卸載後再重新組合（離開 Main → 切換帳號 → 回到 Main），舊組合還在
 * 等的 cookie 清除回呼仍自認是「目前」，可能在新帳號之後裝回舊帳號的 session、記錄舊帳號為 owner、
 * 甚至載入已被卸下的 WebView。
 *
 * 期望：cookie 工作與目標代次是整個 process 共用的（[WebViewCookieCoordinator]）——不同組合的 cookie
 * 工作一律排同一個佇列；組合卸載時交回自己的代次，讓它還沒完成的工作失效。
 *
 * 兩個組合共用同一個 fake cookie store 與同一個 coordinator（等同 process 內的單例）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class OdooWebViewCrossCompositionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var webView: WebView

    private var showMain by mutableStateOf(true)
    private var account by mutableStateOf(ACCOUNT_A)

    private val cookiePlans = mutableMapOf<String, WebViewCookiePlan>()
    private val ownerRecords = mutableListOf<String>()
    private val cookieStore = HeldCookieStore()
    private val coordinator = WebViewCookieCoordinator()

    private fun render() {
        composeRule.setContent {
            if (showMain) {
                OdooWebView(
                    accountId = account.id,
                    serverUrl = SERVER,
                    database = account.database,
                    planCookies = { id, _, _ -> cookiePlans[id] ?: WebViewCookiePlan.Clear },
                    onCookiesApplied = { id, _ -> ownerRecords += id },
                    onWebViewCreated = { webView = it },
                    onLoadingChanged = {},
                    onSelfHeal = { false },
                    getFreshSessionId = { null },
                    onReloginRequired = {},
                    cookieStore = cookieStore,
                    cookieCoordinator = coordinator,
                )
            }
        }
        idle()
    }

    private fun idle() {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun lastLoadedUrl(view: WebView): String? {
        var url: String? = null
        composeRule.runOnUiThread { url = shadowOf(view).lastLoadedUrl }
        return url
    }

    private fun leaveMain() {
        composeRule.runOnUiThread { showMain = false }
        idle()
    }

    private fun enterMain(target: TestAccount) {
        composeRule.runOnUiThread {
            account = target
            showMain = true
        }
        idle()
    }

    private fun completeRemovalsLastFirst() {
        composeRule.runOnUiThread { cookieStore.completeAllLastFirst() }
        idle()
    }

    @Test
    fun `Given A's cookie removal is pending when Main is left, B is chosen and Main re-entered then A's late callback writes, records and loads nothing`() {
        cookiePlans[ACCOUNT_A.id] = WebViewCookiePlan.Replace("sid-a")
        cookiePlans[ACCOUNT_B.id] = WebViewCookiePlan.Replace("sid-b")
        cookieStore.deferRemoval = true
        render()
        val instanceA = webView

        leaveMain()
        enterMain(ACCOUNT_B)
        val instanceB = webView
        assertNotSame(instanceA, instanceB)

        completeRemovalsLastFirst()

        assertTrue("cookie jobs of both compositions never interleave", cookieStore.maxOutstandingRemovals <= 1)
        assertFalse("A's late callback writes no A session", cookieStore.sessionWrites.contains("session_id=sid-a"))
        assertEquals("session_id=sid-b", cookieStore.sessionWrites.last())
        assertEquals("only B is recorded as the cookie owner", listOf(ACCOUNT_B.id), ownerRecords)
        assertNull("the left composition's WebView loads nothing", lastLoadedUrl(instanceA))
        assertEquals("$SERVER/web?db=${ACCOUNT_B.database}", lastLoadedUrl(instanceB))
    }

    @Test
    fun `Given a cookie job is pending when Main is left then the job turns inert`() {
        cookiePlans[ACCOUNT_A.id] = WebViewCookiePlan.Replace("sid-a")
        cookieStore.deferRemoval = true
        render()
        val instanceA = webView

        leaveMain()
        completeRemovalsLastFirst()

        assertTrue("no session is written after Main left", cookieStore.sessionWrites.isEmpty())
        assertTrue("no owner is recorded after Main left", ownerRecords.isEmpty())
        assertNull("the left WebView loads nothing", lastLoadedUrl(instanceA))
    }

    @Test
    fun `Given Main is left and re-entered for the same account then the new composition prepares cookies and loads again`() {
        cookiePlans[ACCOUNT_A.id] = WebViewCookiePlan.Replace("sid-a")
        render()
        val first = webView
        assertEquals("$SERVER/web?db=${ACCOUNT_A.database}", lastLoadedUrl(first))

        leaveMain()
        enterMain(ACCOUNT_A)

        assertNotSame(first, webView)
        assertEquals("$SERVER/web?db=${ACCOUNT_A.database}", lastLoadedUrl(webView))
        assertEquals(listOf(ACCOUNT_A.id, ACCOUNT_A.id), ownerRecords)
    }

    // --- The process-wide coordinator itself ---------------------------------------------------------

    @Test
    fun `A new target supersedes every earlier token`() {
        val c = WebViewCookieCoordinator()
        val a = c.beginTarget()
        val b = c.beginTarget()
        assertFalse(c.isCurrent(a))
        assertTrue(c.isCurrent(b))
    }

    @Test
    fun `Releasing the current token makes it inert and releasing a superseded one changes nothing`() {
        val c = WebViewCookieCoordinator()
        val a = c.beginTarget()
        val b = c.beginTarget()
        c.release(a)
        assertTrue("a superseded composition leaving must not invalidate the current one", c.isCurrent(b))
        c.release(b)
        assertFalse(c.isCurrent(b))
    }

    @Test
    fun `A queued job of a superseded token is dropped before it starts`() {
        val c = WebViewCookieCoordinator()
        val a = c.beginTarget()
        var releaseFirst: () -> Unit = {}
        val ran = mutableListOf<String>()
        c.sequencer.enqueue({ c.isCurrent(a) }) { done -> ran += "a1"; releaseFirst = done }
        c.sequencer.enqueue({ c.isCurrent(a) }) { done -> ran += "a2"; done() }
        val b = c.beginTarget()
        c.sequencer.enqueue({ c.isCurrent(b) }) { done -> ran += "b"; done() }

        releaseFirst()

        assertEquals(listOf("a1", "b"), ran)
    }

    @Test
    fun `A job that throws does not wedge the process-wide queue`() {
        val sequencer = WebViewCookieSequencer()
        runCatching { sequencer.enqueue({ true }) { _ -> error("boom") } }
        var ran = false
        sequencer.enqueue({ true }) { done -> ran = true; done() }
        assertTrue("the next job still runs", ran)
    }

    /** Cookie store whose removals can be held and completed newest-first; counts overlapping removals. */
    private class HeldCookieStore : WebViewCookieStore {
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
    }
}
