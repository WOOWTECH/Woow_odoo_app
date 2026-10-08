package io.woowtech.odoo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 2026-10-08 contrast pass on the login / App Lock error states, light and dark:
 *  - error banners ("wrong login or password", PIN errors, lockout) are errorContainer + onErrorContainer;
 *  - field error labels and messages on the login form are `error` on the screen background.
 * Dark `error` was #D32F2F on #121212 = 3.76:1; it is now [ErrorColorDark] (4.95:1). Light is unchanged.
 */
class ErrorContrastTest {

    private fun channel(value: Float): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private fun schemes(primary: Long): List<Pair<String, ColorScheme>> =
        listOf("light" to createLightColorScheme(Color(primary)), "dark" to createDarkColorScheme(Color(primary)))

    private fun check(label: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        println("CONTRAST $label ${hex(fg)} on ${hex(bg)} = ${"%.2f".format(ratio)}")
        assertTrue(ratio >= 4.5, "$label ${hex(fg)} on ${hex(bg)} = $ratio")
    }

    @ParameterizedTest(name = "primary #{0}")
    @ValueSource(longs = [0xFF6183FC, 0xFF8B6B24])
    fun `Given light and dark themes when an error banner is shown then its text reaches WCAG AA`(primary: Long) {
        for ((mode, scheme) in schemes(primary)) {
            check("$mode banner", scheme.onErrorContainer, scheme.errorContainer)
        }
    }

    @ParameterizedTest(name = "primary #{0}")
    @ValueSource(longs = [0xFF6183FC, 0xFF8B6B24])
    fun `Given light and dark themes when a field error is shown then error text reaches WCAG AA`(primary: Long) {
        for ((mode, scheme) in schemes(primary)) {
            check("$mode field error on background", scheme.error, scheme.background)
            check("$mode field error on surface", scheme.error, scheme.surface)
            check("$mode onError on error", scheme.onError, scheme.error)
        }
    }

    @ParameterizedTest(name = "primary #{0}")
    @ValueSource(longs = [0xFF6183FC, 0xFF8B6B24])
    fun `Given the light theme when the error colour is read then it is unchanged`(primary: Long) {
        val light = createLightColorScheme(Color(primary))
        assertEquals(ErrorColor, light.error)
        assertEquals(Color.White, light.onError)
        assertEquals(ErrorColorDark, createDarkColorScheme(Color(primary)).error)
    }
}
