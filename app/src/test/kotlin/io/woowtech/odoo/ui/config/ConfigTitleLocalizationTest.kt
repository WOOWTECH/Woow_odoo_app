package io.woowtech.odoo.ui.config

import android.app.Application
import io.woowtech.odoo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * LIVE-0927 Android r2（證據 36／37）：繁中、簡中的設定面板標題（configuration）與面板裡的選項
 * （settings）同名，都叫「設定」／「设置」。對齊 iOS 32462a3：面板標題改「帳號與設定」／「账号与设置」，
 * 選項維持「設定」／「设置」；英文 Configuration／Settings 不變。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConfigTitleLocalizationTest {

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `Given Traditional Chinese then the panel title differs from its Settings option`() {
        assertEquals("帳號與設定", string(R.string.configuration))
        assertEquals("設定", string(R.string.settings))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Given Simplified Chinese then the panel title differs from its Settings option`() {
        assertEquals("账号与设置", string(R.string.configuration))
        assertEquals("设置", string(R.string.settings))
    }

    @Test
    @Config(qualifiers = "en")
    fun `Given English then the titles are unchanged and distinct`() {
        assertEquals("Configuration", string(R.string.configuration))
        assertEquals("Settings", string(R.string.settings))
        assertNotEquals(string(R.string.configuration), string(R.string.settings))
    }
}
