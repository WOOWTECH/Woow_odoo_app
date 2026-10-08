package io.woowtech.odoo.ui.main

import androidx.compose.ui.graphics.Color
import io.woowtech.odoo.ui.theme.createDarkColorScheme
import io.woowtech.odoo.ui.theme.createLightColorScheme
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * W2-4 U9 (Pixel 7a, Play vc5): the "Notifications are off" banner's Dismiss / Turn on buttons were the
 * theme primary (Apporo gold #8B6B24) on the dark-red error container — poor contrast. Text and buttons
 * must reach WCAG AA (4.5:1) on the banner in light and dark, for both brand colours and any theme colour
 * the user picks, because the buttons no longer use the primary at all.
 */
class NotificationBannerContrastTest {

    private fun channel(value: Float): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @ParameterizedTest(name = "primary #{0}")
    @ValueSource(longs = [0xFF6183FC, 0xFF8B6B24, 0xFFFFD54F, 0xFFB71C1C])
    fun `Given light and dark themes when the banner is drawn then text and buttons reach WCAG AA`(primary: Long) {
        for (scheme in listOf(createLightColorScheme(Color(primary)), createDarkColorScheme(Color(primary)))) {
            val colors = notificationBannerColors(scheme)
            assertTrue(contrast(colors.content, colors.container) >= 4.5, "text ${contrast(colors.content, colors.container)}")
            assertTrue(contrast(colors.action, colors.container) >= 4.5, "buttons ${contrast(colors.action, colors.container)}")
            assertNotEquals(scheme.primary, colors.action, "buttons must not follow the theme primary")
        }
    }
}
