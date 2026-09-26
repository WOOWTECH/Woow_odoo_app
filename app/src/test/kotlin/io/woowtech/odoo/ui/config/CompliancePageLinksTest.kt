package io.woowtech.odoo.ui.config

import android.app.Application
import org.robolectric.RuntimeEnvironment
import io.woowtech.odoo.R
import io.woowtech.odoo.BuildConfig
import io.woowtech.odoo.brand.AppBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 設定頁「支援／隱私權政策／刪除帳號」三個入口的網址契約（build 22，送審需求 R1/R2/O7）。
 *
 * 網址放在各語系的字串資源裡，所以開哪一頁由「畫面實際用哪份字串」決定：
 *  - 英文 UI（values/，也是其他未翻譯語系的後備）→ `-en` 頁
 *  - 繁中、簡中 UI → 無後綴頁
 *
 * 每個語系都驗，並確認不再有任何 odoo.com 連結（避免暗示與 Odoo S.A. 有關）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompliancePageLinksTest {

    private fun str(id: Int): String =
        RuntimeEnvironment.getApplication().getString(id)

    // woowtechName: engineering b1b052f gives the WOOW flavor a zh-TW-only name (渥屋平台);
    // the Apporo flavor stays "Apporo platform" in every locale.
    private fun assertLinks(suffix: String, woowtechName: String = "woowtech platform") {
        val origin = if (BuildConfig.APP_BRAND == "apporo") "https://www.apporo.ai" else "https://aiot.woowtech.io"
        assertEquals(R.string.url_support, AppBrand.current.supportUrlResource)
        assertEquals(R.string.url_privacy_policy, AppBrand.current.privacyUrlResource)
        assertEquals(R.string.url_account_deletion, AppBrand.current.deletionUrlResource)
        if (BuildConfig.APP_BRAND == "apporo") {
            assertEquals("Apporo platform", str(R.string.app_name))
            assertEquals("Apporo platform", str(R.string.notification_channel_messages))
            assertEquals("Apporo platform / APPORO UNION INC.", str(R.string.copyright))
        } else {
            assertEquals(woowtechName, str(R.string.app_name))
            assertEquals("© 2026 WoowTech", str(R.string.copyright))
        }
        assertEquals("$origin/odoo-support$suffix", str(R.string.url_support))
        assertEquals("$origin/odoo-privacy$suffix", str(R.string.url_privacy_policy))
        assertEquals(
            "$origin/odoo-account-deletion$suffix",
            str(R.string.url_account_deletion)
        )
        listOf(R.string.url_support, R.string.url_privacy_policy, R.string.url_account_deletion)
            .forEach { assertFalse(str(it).contains("odoo.com")) }
    }

    @Test
    @Config(qualifiers = "en")
    fun `Given English UI when opening compliance links then the -en pages are used`() {
        assertLinks("-en")
        assertEquals("Delete Account", str(R.string.delete_account_title))
    }

    @Test
    @Config(qualifiers = "ja")
    fun `Given an untranslated locale when UI falls back to English then the -en pages are used`() {
        assertLinks("-en")
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `Given Traditional Chinese UI when opening compliance links then the non-suffixed pages are used`() {
        assertLinks("", woowtechName = "渥屋平台")
        assertEquals("刪除帳號", str(R.string.delete_account_title))
        assertEquals("隱私權政策", str(R.string.privacy_policy_title))
        assertEquals("支援", str(R.string.support_title))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Given Simplified Chinese UI when opening compliance links then the non-suffixed pages are used`() {
        assertLinks("")
        assertEquals("删除账号", str(R.string.delete_account_title))
    }
}
