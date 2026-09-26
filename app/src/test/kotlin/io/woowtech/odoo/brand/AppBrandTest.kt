package io.woowtech.odoo.brand

import io.woowtech.odoo.BuildConfig
import io.woowtech.odoo.domain.model.AppSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.pow

/** IMPL-SPEC B01/B03/B04: independent expected values for both brands, never provider self-proof. */
class AppBrandTest {
    @Test
    fun `Given WOOW when resolving brand then legacy identity and every color role remain unchanged`() {
        val brand = AppBrand.forCode("woowtech")
        assertEquals("#6183FC", brand.primaryHex)
        assertEquals("https://aiot.woowtech.io", brand.website)
        assertEquals("woowtech@designsmart.com.tw", brand.supportEmail)
        assertEquals("woowodoo", brand.scheme)
        assertEquals("woowodoo", AppBrand.forCode("woowtech", true).scheme)
        assertEquals("WoowTech", brand.webLogTag)
        assertEquals(listOf(0xFFDBE1FF, 0xFF3A4B8C, 0xFF001A41, 0xFFDBE1FF,
            0xFF4A6AE0, 0xFF8BA3FF, 0xFF65C2E0),
            listOf(brand.lightContainer, brand.darkContainer, brand.onLightContainer,
                brand.onDarkContainer, brand.darkPrimary, brand.lightPrimary, brand.secondary))
    }

    @Test
    fun `Given Apporo when resolving brand then identity and deterministic palette are independent`() {
        val brand = AppBrand.forCode("apporo")
        assertEquals("#8B6B24", brand.primaryHex)
        assertEquals("https://www.apporo.ai", brand.website)
        assertEquals("info@apporo.ai", brand.supportEmail)
        assertEquals("apporoodoo", brand.scheme)
        assertEquals("apporoodoo-dev", AppBrand.forCode("apporo", true).scheme)
        assertEquals("Apporo", brand.webLogTag)
        assertEquals("Apporo", AppBrand.forCode("apporo", true).webLogTag)
        assertEquals(listOf(0xFFEEE9DE, 0xFF4C3B14, 0xFF241C09, 0xFFEEE9DE,
            0xFF4C3B14, 0xFFEEE9DE, 0xFF8B6B24),
            listOf(brand.lightContainer, brand.darkContainer, brand.onLightContainer,
                brand.onDarkContainer, brand.darkPrimary, brand.lightPrimary, brand.secondary))
    }

    @Test
    fun `Given Apporo textual roles when measuring WCAG then light and dark contrast exceed AA`() {
        val brand = AppBrand.forCode("apporo")
        for ((background, foreground) in listOf(brand.primaryArgb to 0xFFFFFFFF,
            brand.secondary to 0xFFFFFFFF, brand.lightContainer to brand.onLightContainer,
            brand.darkContainer to brand.onDarkContainer)) {
            val a = luminance(background)
            val b = luminance(foreground)
            assertTrue((maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05) >= 4.5)
        }
    }

    @Test
    fun `Given unknown brand when resolving then it fails instead of falling back to WOOW`() {
        assertThrows(IllegalStateException::class.java) { AppBrand.forCode("unknown") }
    }

    @Test
    fun `Given missing preference when constructing settings then selected brand defaults but saved color survives`() {
        val expected = if (BuildConfig.APP_BRAND == "apporo") "#8B6B24" else "#6183FC"
        assertEquals(expected, AppSettings().themeColor)
        assertEquals("#123456", AppSettings(themeColor = "#123456").themeColor)
    }

    private fun luminance(argb: Long): Double = listOf(16, 8, 0).map { shift ->
        val c = ((argb shr shift) and 255) / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }.let { it[0] * 0.2126 + it[1] * 0.7152 + it[2] * 0.0722 }
}
