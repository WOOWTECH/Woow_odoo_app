package io.woowtech.odoo.data.push

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.OdooAccount
import io.woowtech.odoo.ui.main.DeepLinkWebPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 外部連結導頁（擁有者 2026-09-29 核准，對齊 iOS `odooApp.handleIncomingURL`，兩品牌共用）：
 * `<品牌 scheme>://open?url=<encoded>` 的 VIEW intent → 取 `url` 參數 → 以目前帳號伺服器跑共用
 * [DeepLinkValidator] → 綁定目前帳號放進 [DeepLinkManager]，由既有 WebView 載入後導頁流程套用。
 *
 * 以 iOS 為準、不比 iOS 寬鬆：絕對網址須與目前帳號同源（https）；相對路徑只收 `^/web([/?#]|$)`；
 * javascript:／外站／其他路徑一律忽略；缺 `url` 只開 App；沒有登入中的帳號（登出後）不導頁。
 * 推播點擊（帶 [NotificationHelper.EXTRA_ACTION_URL]）仍走原本的推播路由，這裡不碰。
 *
 * scheme 取自 [AppBrand.current]，所以 woowtech／apporo 兩個 unit test task 各驗自己的品牌 scheme。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalLinkIntakeTest {

    private val scheme = AppBrand.current.scheme
    private val server = "https://www.apporo.ai"
    private val account = OdooAccount(
        id = "acc-active",
        serverUrl = server,
        database = "odoo",
        username = "admin",
        displayName = "Admin",
        isActive = true,
    )

    private val deepLinkManager = DeepLinkManager()

    private fun intake(active: OdooAccount? = account): ExternalLinkIntake {
        val accountRepository = mockk<AccountRepository>(relaxed = true) {
            every { activeAccount } returns MutableStateFlow(active)
        }
        return ExternalLinkIntake(accountRepository, deepLinkManager)
    }

    private fun viewIntent(data: String) = Intent(Intent.ACTION_VIEW, Uri.parse(data))

    private fun openLink(encodedUrl: String) = viewIntent("$scheme://open?url=$encodedUrl")

    /** What the active account's WebView would load once its page finishes (the existing apply flow). */
    private fun plannedLoad(): String? {
        val pending = deepLinkManager.consumeFor(account.id) ?: return null
        val plan = DeepLinkWebPlanner.plan(currentUrl = null, serverUrl = account.fullServerUrl, deepLink = pending)
        return (plan as? DeepLinkWebPlanner.NavPlan.FullLoad)?.url
    }

    @Test
    fun `Given signed in when a VIEW link carries a relative web path then the WebView loads it on the same server`() = runTest {
        val accepted = intake().accept(openLink("%2Fweb%23action%3Dcalendar.action_calendar_event"))

        assertTrue(accepted)
        assertEquals("$server/web#action=calendar.action_calendar_event", plannedLoad())
    }

    @Test
    fun `Given signed in when a VIEW link carries a same-server absolute URL then the WebView loads that URL`() = runTest {
        val accepted = intake().accept(openLink("https%3A%2F%2Fwww.apporo.ai%2Fodoo%2Fcontacts"))

        assertTrue(accepted)
        assertEquals("https://www.apporo.ai/odoo/contacts", plannedLoad())
    }

    @Test
    fun `Given signed in when a VIEW link carries a rejected target then nothing is navigated`() = runTest {
        val rejected = listOf(
            "https%3A%2F%2Fevil.example.com%2Fweb", // other host
            "http%3A%2F%2Fwww.apporo.ai%2Fweb", // same host but not https
            "javascript%3Aalert(1)",
            "data%3Atext%2Fhtml%2C%3Ch1%3Ex%3C%2Fh1%3E",
            "%2Fodoo%2Fcontacts", // relative but not /web (iOS accepts only ^/web)
            "%2Fwebsite%2Finfo",
            "%2Fweb%2F..%2Fsecret",
            "", // empty url parameter
        )
        for (target in rejected) {
            assertFalse(target, intake().accept(openLink(target)))
            assertNull(target, deepLinkManager.pending.value)
        }
    }

    @Test
    fun `Given signed in when a VIEW link needs the shared push-tap rules then it is rejected like a push tap`() = runTest {
        // 2026-09-29 push-link approval: the intake's own checks moved into DeepLinkValidator; these
        // cover what the shared validator now rejects for both push taps and external links.
        val rejected = listOf(
            "%2F%2Fwww.apporo.ai%2Fweb", // scheme-relative, no https
            "%2Fweb%2F%252E%252E%2Fweb", // upper-case encoded traversal (decodes to /web/%2E%2E/web)
            "%2Fweb%2F.%252e%2Fweb", // half-encoded traversal (decodes to /web/.%2e/web)
            "%2Fweb%23id%3D1%E2%80%AE", // right-to-left override (format character)
            "intent%3A%2F%2Fwww.apporo.ai%2Fweb%23Intent%3Bend",
        )
        for (target in rejected) {
            assertFalse(target, intake().accept(openLink(target)))
            assertNull(target, deepLinkManager.pending.value)
        }
    }

    @Test
    fun `Given signed in when a VIEW link has no url parameter then the app only opens`() = runTest {
        assertFalse(intake().accept(viewIntent("$scheme://open")))
        assertFalse(intake().accept(viewIntent("$scheme://open?next=%2Fweb")))
        assertNull(deepLinkManager.pending.value)
    }

    @Test
    fun `Given signed out when a valid VIEW link arrives then nothing is navigated`() = runTest {
        assertFalse(intake(active = null).accept(openLink("%2Fweb%23action%3Dcalendar.action_calendar_event")))
        assertNull(deepLinkManager.pending.value)
    }

    @Test
    fun `Given a link for another scheme or host then it is ignored`() = runTest {
        val otherScheme = if (scheme == "woowodoo") "apporoodoo" else "woowodoo"
        assertFalse(intake().accept(viewIntent("$otherScheme://open?url=%2Fweb")))
        assertFalse(intake().accept(viewIntent("$scheme://close?url=%2Fweb")))
        assertFalse(intake().accept(viewIntent("https://www.apporo.ai/web")))
        assertNull(deepLinkManager.pending.value)
    }

    @Test
    fun `Given a notification tap or launcher intent then the external link intake leaves it alone`() = runTest {
        val push = openLink("%2Fweb").putExtra(NotificationHelper.EXTRA_ACTION_URL, "/web#id=7")
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        assertFalse(intake().accept(push))
        assertFalse(intake().accept(launcher))
        assertFalse(intake().accept(null))
        assertNull(deepLinkManager.pending.value)
    }
}
