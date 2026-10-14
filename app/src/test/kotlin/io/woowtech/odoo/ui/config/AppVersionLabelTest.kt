package io.woowtech.odoo.ui.config

import io.woowtech.odoo.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * W2-4 L3 (Pixel 7a, Play vc5): Settings showed only "1.0", so testers could not tell which build was
 * installed. Now `versionName (versionCode)` for both brands.
 */
class AppVersionLabelTest {

    @Test
    fun `Given the Apporo release version when labelled then name and build number`() {
        assertEquals("1.0 (8)", appVersionLabel(versionName = "1.0", versionCode = 8))
    }

    @Test
    fun `Given the WOOW release version when labelled then name and build number`() {
        assertEquals("1.4.3 (24)", appVersionLabel(versionName = "1.4.3", versionCode = 24))
    }

    @Test
    fun `Given no argument then this build's own version is shown`() {
        assertEquals("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", appVersionLabel())
    }
}
