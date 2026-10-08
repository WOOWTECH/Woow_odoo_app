package io.woowtech.odoo.ui.config

import io.woowtech.odoo.domain.model.AppLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * W2-4 en round (Pixel 7a, Play vc5): with the per-app locale set to en-US outside the app
 * (`cmd locale set-app-locales`, or system Settings › Apps › Language) the UI was English but Settings
 * still said "System Default" — it showed the app's own stored choice. The system per-app locale now wins
 * whenever it is known (Android 13+).
 */
class EffectiveAppLanguageTest {

    @Test
    fun `Given a system per-app locale when the stored choice is System then the real language is shown`() {
        assertEquals(AppLanguage.ENGLISH, effectiveAppLanguage("en-US", AppLanguage.SYSTEM))
        assertEquals(AppLanguage.CHINESE_TW, effectiveAppLanguage("zh-TW", AppLanguage.SYSTEM))
        assertEquals(AppLanguage.CHINESE_CN, effectiveAppLanguage("zh-CN", AppLanguage.SYSTEM))
    }

    @Test
    fun `Given Chinese tags with scripts or regions then Traditional and Simplified are told apart`() {
        assertEquals(AppLanguage.CHINESE_TW, effectiveAppLanguage("zh-Hant-TW", AppLanguage.SYSTEM))
        assertEquals(AppLanguage.CHINESE_TW, effectiveAppLanguage("zh-HK", AppLanguage.SYSTEM))
        assertEquals(AppLanguage.CHINESE_CN, effectiveAppLanguage("zh-Hans", AppLanguage.SYSTEM))
        assertEquals(AppLanguage.CHINESE_CN, effectiveAppLanguage("zh", AppLanguage.SYSTEM))
    }

    @Test
    fun `Given the system follows the device when a language was stored then System Default is shown`() {
        assertEquals(AppLanguage.SYSTEM, effectiveAppLanguage("", AppLanguage.ENGLISH))
    }

    @Test
    fun `Given several tags then the first one decides`() {
        assertEquals(AppLanguage.ENGLISH, effectiveAppLanguage("en-GB,zh-TW", AppLanguage.CHINESE_TW))
    }

    @Test
    fun `Given a language the app does not offer then System Default`() {
        assertEquals(AppLanguage.SYSTEM, effectiveAppLanguage("ja-JP", AppLanguage.ENGLISH))
    }

    @Test
    fun `Given the system locale is unknown below Android 13 then the stored choice is kept`() {
        for (stored in AppLanguage.entries) {
            assertEquals(stored, effectiveAppLanguage(null, stored))
        }
    }
}
