package io.woowtech.odoo.ui.theme

import androidx.compose.ui.graphics.Color
import io.woowtech.odoo.brand.AppBrand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 2026-10-08 contrast pass: white text on the WOOW blue #6183FC filled buttons (login Next / Log in, offline
 * Retry, App Lock unlock) is 3.41:1, below WCAG AA 4.5:1. Filled buttons on the brand primary now use
 * [AppBrand.solidButtonArgb] — same hue, darker — while the primary itself (top bars, icons) is unchanged,
 * Apporo #8B6B24 (4.97:1) is untouched and a theme colour the user picks is passed through as before.
 */
class SolidButtonContrastTest {

    private fun channel(value: Float): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun hueDegrees(color: Color): Double {
        val r = color.red.toDouble()
        val g = color.green.toDouble()
        val b = color.blue.toDouble()
        val hi = max(r, max(g, b))
        val d = hi - min(r, min(g, b))
        val sector = when (hi) {
            r -> ((g - b) / d).mod(6.0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return sector * 60
    }

    private val woow = AppBrand.forCode("woowtech")
    private val apporo = AppBrand.forCode("apporo")

    @Test
    fun `Given WOOW blue when white text sits on it then it is below AA and the button colour reaches AA`() {
        val primary = Color(woow.primaryArgb)
        val button = Color(woow.solidButtonArgb)
        assertTrue(contrast(Color.White, primary) < 4.5, "primary ${contrast(Color.White, primary)}")
        assertTrue(contrast(Color.White, button) >= 4.5, "button ${contrast(Color.White, button)}")
        assertEquals(Color(0xFF4069FB), button)
        assertTrue(abs(hueDegrees(button) - hueDegrees(primary)) < 0.5, "hue ${hueDegrees(button)} vs ${hueDegrees(primary)}")
        assertTrue(luminance(button) < luminance(primary), "only a deeper shade of the same blue")
    }

    @Test
    fun `Given Apporo gold when used as a button then it already reaches AA and is not changed`() {
        assertEquals(apporo.primaryArgb, apporo.solidButtonArgb)
        assertTrue(contrast(Color.White, Color(apporo.solidButtonArgb)) >= 4.5)
    }

    @Test
    fun `Given this flavor's brand primary when a filled button is drawn then white text reaches AA in light and dark`() {
        for (scheme in listOf(createLightColorScheme(BrandPrimaryBlue), createDarkColorScheme(BrandPrimaryBlue))) {
            val container = solidButtonContainer(scheme.primary)
            assertEquals(BrandSolidButton, container)
            assertTrue(contrast(scheme.onPrimary, container) >= 4.5, "button ${contrast(scheme.onPrimary, container)}")
            assertEquals(BrandPrimaryBlue, scheme.primary, "the primary role itself is unchanged")
        }
    }

    @ParameterizedTest(name = "user colour #{0}")
    @ValueSource(longs = [0xFF00C853, 0xFFFFD54F, 0xFFB71C1C, 0xFF6200EE])
    fun `Given a theme colour the user picked when a filled button is drawn then it keeps that colour`(picked: Long) {
        assertEquals(Color(picked), solidButtonContainer(Color(picked)))
    }
}
