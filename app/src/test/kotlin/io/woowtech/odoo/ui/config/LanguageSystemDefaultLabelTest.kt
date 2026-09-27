package io.woowtech.odoo.ui.config

import android.app.Application
import io.woowtech.odoo.R
import io.woowtech.odoo.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * LIVE-0927 Android r2：設定頁語言選項「System Default」在繁中／簡中介面也顯示英文
 * （AppSettings.kt:26 寫死的 `displayName`，SettingsScreen.kt:301 與語言選單直接顯示）。
 * 「跟隨系統」改用三語字串資源；其他語言維持各自的原文名稱（English／繁體中文／简体中文）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LanguageSystemDefaultLabelTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `System follows a string resource while named languages keep their endonyms`() {
        assertEquals(R.string.language_system, appLanguageLabelRes(AppLanguage.SYSTEM))
        assertNull(appLanguageLabelRes(AppLanguage.ENGLISH))
        assertNull(appLanguageLabelRes(AppLanguage.CHINESE_TW))
        assertNull(appLanguageLabelRes(AppLanguage.CHINESE_CN))
        assertEquals("English", appLanguageLabel(context, AppLanguage.ENGLISH))
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `Given Traditional Chinese then System Default is localized`() {
        assertEquals("跟隨系統", appLanguageLabel(context, AppLanguage.SYSTEM))
        assertEquals("繁體中文", appLanguageLabel(context, AppLanguage.CHINESE_TW))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Given Simplified Chinese then System Default is localized`() {
        assertEquals("跟随系统", appLanguageLabel(context, AppLanguage.SYSTEM))
    }

    @Test
    @Config(qualifiers = "en")
    fun `Given English then System Default reads in English`() {
        assertEquals("System Default", appLanguageLabel(context, AppLanguage.SYSTEM))
    }
}
