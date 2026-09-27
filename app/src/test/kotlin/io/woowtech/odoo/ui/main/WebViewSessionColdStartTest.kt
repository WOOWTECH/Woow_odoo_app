package io.woowtech.odoo.ui.main

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.api.SessionReauthenticator
import io.woowtech.odoo.data.local.WebViewCookieOwnerStore
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.ReloginSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * LIVE-0927 Android r2（證據 59／60、59-coldstart-no-remember.logcat.txt、reauth-logcat.txt）：
 * App 程序重啟後，WebView 仍有效的 Odoo session 被 `isolateCookiesForAccount` 整批清掉。
 *
 * 根因：WebView 建立時一律 `removeAllCookies`，再塞回 `OdooJsonRpcClient` 記憶體 cookie jar 的
 * session_id；程序重啟後 jar 是空的，所以什麼都沒塞回。
 *  - 不勾「記住我」（W1-10 9617c40 起不存密碼）→ `/web/login` → self-heal「no stored credentials」
 *    → 被丟到 Configuration 頁出不去。
 *  - 勾「記住我」→ 每次冷啟動都在背景重新登入一次。
 *
 * 修正：記住 WebView cookie 屬於哪個帳號（[WebViewCookieOwnerStore]），同一帳號且 WebView 仍有
 * session cookie 就保留；帳號不同一律清掉（phase3 §2.4：舊帳號 cookie 不得汙染新帳號）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewSessionColdStartTest {

    @TempDir
    lateinit var dir: File

    private val accountA = "account-a"
    private val accountB = "account-b"

    // --- Planner ------------------------------------------------------------------------------

    @Test
    fun `Given WebView still holds the active account's own session when the process restarted then the cookie is kept`() {
        val plan = WebViewCookiePlanner.plan(
            accountId = accountA,
            cookieOwnerAccountId = accountA,
            webViewHasSessionCookie = true,
            nativeSessionId = null, // in-memory jar is empty after a process restart
            lastInjectedSessionId = null,
        )

        assertEquals(WebViewCookiePlan.KeepExisting, plan)
    }

    @Test
    fun `Given remember me on and a still valid own cookie when cold starting then nothing is replaced so no silent re-login is triggered`() {
        // Replacing/clearing is what sends the WebView to /web/login and triggers self-heal re-auth.
        val plan = WebViewCookiePlanner.plan(accountA, accountA, true, null, null)

        assertFalse(plan is WebViewCookiePlan.Clear || plan is WebViewCookiePlan.Replace)
    }

    @Test
    fun `Given cookies owned by account A when account B becomes active without a native session then all cookies are cleared`() {
        val plan = WebViewCookiePlanner.plan(accountB, accountA, true, null, null)

        assertEquals(WebViewCookiePlan.Clear, plan)
    }

    @Test
    fun `Given cookies owned by account A when account B becomes active with its own native session then B's session replaces them`() {
        val plan = WebViewCookiePlanner.plan(accountB, accountA, true, "sid-b", "sid-a")

        assertEquals(WebViewCookiePlan.Replace("sid-b"), plan)
    }

    @Test
    fun `Given unknown cookie owner when cold starting then cookies are cleared`() {
        assertEquals(WebViewCookiePlan.Clear, WebViewCookiePlanner.plan(accountA, null, true, null, null))
    }

    @Test
    fun `Given the user just signed in again in this process when the WebView still has the expired cookie then the fresh native session wins`() {
        val plan = WebViewCookiePlanner.plan(accountA, accountA, true, "sid-fresh", "sid-old")

        assertEquals(WebViewCookiePlan.Replace("sid-fresh"), plan)
    }

    @Test
    fun `Given the native session was already installed when the WebView is recreated then Odoo's rotated cookie is not overwritten`() {
        val plan = WebViewCookiePlanner.plan(accountA, accountA, true, "sid-installed", "sid-installed")

        assertEquals(WebViewCookiePlan.KeepExisting, plan)
    }

    @Test
    fun `Given own account but the WebView has no session cookie and no native session then cookies are cleared`() {
        assertEquals(WebViewCookiePlan.Clear, WebViewCookiePlanner.plan(accountA, accountA, false, null, null))
    }

    @Test
    fun `session cookie detection reads the CookieManager header`() {
        assertTrue(WebViewCookiePlanner.hasSessionCookie("frontend_lang=en_US; session_id=abc123"))
        assertTrue(WebViewCookiePlanner.hasSessionCookie("session_id=abc123"))
        assertFalse(WebViewCookiePlanner.hasSessionCookie("frontend_lang=en_US"))
        assertFalse(WebViewCookiePlanner.hasSessionCookie("session_id="))
        assertFalse(WebViewCookiePlanner.hasSessionCookie("old_session_id=x"))
        assertFalse(WebViewCookiePlanner.hasSessionCookie(null))
    }

    // --- Owner store (survives the process restart) --------------------------------------------

    @Test
    fun `Given owner recorded when the process restarts then the owner survives but the in-memory injected id does not`() {
        val file = File(dir, WebViewCookieOwnerStore.FILE_NAME)
        WebViewCookieOwnerStore(file).recordInstalled(accountA, "sid-a")

        val afterRestart = WebViewCookieOwnerStore(file)

        assertEquals(accountA, afterRestart.ownerAccountId())
        assertNull(afterRestart.lastInjectedSessionId)
    }

    @Test
    fun `Given cookies cleared when the process restarts then no account owns them`() {
        val file = File(dir, WebViewCookieOwnerStore.FILE_NAME)
        WebViewCookieOwnerStore(file).apply {
            recordInstalled(accountA, "sid-a")
            recordCleared()
        }

        assertNull(WebViewCookieOwnerStore(file).ownerAccountId())
    }

    // --- MainViewModel wiring --------------------------------------------------------------------

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var sessionReauthenticator: SessionReauthenticator

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountRepository = mockk(relaxed = true)
        sessionReauthenticator = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(store: WebViewCookieOwnerStore): MainViewModel {
        val reloginSignal = mockk<ReloginSignal>(relaxed = true)
        every { reloginSignal.pending } returns MutableStateFlow(null)
        return MainViewModel(
            accountRepository = accountRepository,
            encryptedPrefs = mockk(relaxed = true),
            deepLinkManager = mockk(relaxed = true),
            reloginSignal = reloginSignal,
            locationPermissionGate = mockk(relaxed = true),
            sessionReauthenticator = sessionReauthenticator,
            cookieOwnerStore = store,
        )
    }

    @Test
    fun `Given a session installed before the process died when the new process plans the WebView cookies then it keeps them without re-auth`() {
        val file = File(dir, WebViewCookieOwnerStore.FILE_NAME)
        val beforeDeath = viewModel(WebViewCookieOwnerStore(file))
        every { accountRepository.getSessionId("https://odoo.example.com") } returns "sid-a"
        beforeDeath.onWebViewCookiesApplied(accountA, WebViewCookiePlan.Replace("sid-a"))

        // New process: new store instance, empty native jar.
        every { accountRepository.getSessionId(any()) } returns null
        val afterRestart = viewModel(WebViewCookieOwnerStore(file))
        val plan = afterRestart.planWebViewCookies(accountA, "https://odoo.example.com", webViewHasSessionCookie = true)

        assertEquals(WebViewCookiePlan.KeepExisting, plan)
        verify(exactly = 0) { sessionReauthenticator.reauthenticateForHost(any()) }
    }

    @Test
    fun `Given the WebView cookies were cleared when planning for any account then nothing is kept`() {
        val store = WebViewCookieOwnerStore(File(dir, WebViewCookieOwnerStore.FILE_NAME))
        val vm = viewModel(store)
        vm.onWebViewCookiesApplied(accountA, WebViewCookiePlan.Replace("sid-a"))
        vm.onWebViewCookiesApplied(accountA, WebViewCookiePlan.Clear)
        every { accountRepository.getSessionId(any()) } returns null

        assertEquals(WebViewCookiePlan.Clear, vm.planWebViewCookies(accountA, "https://odoo.example.com", true))
    }

    @Test
    fun `Given self-heal refreshed the session when recorded then the refreshed session owns the WebView`() {
        val store = WebViewCookieOwnerStore(File(dir, WebViewCookieOwnerStore.FILE_NAME))
        val vm = viewModel(store)

        vm.onWebViewCookiesApplied(accountB, WebViewCookiePlan.Replace("sid-healed"))

        assertEquals(accountB, store.ownerAccountId())
        assertEquals("sid-healed", store.lastInjectedSessionId)
    }
}
