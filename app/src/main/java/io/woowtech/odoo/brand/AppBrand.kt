package io.woowtech.odoo.brand

import io.woowtech.odoo.BuildConfig
import io.woowtech.odoo.R
import kotlin.math.roundToInt

/** Build-selected identity, usable from storage/static helpers without Compose or a Context. */
class AppBrand private constructor(
    val code: String,
    val primaryHex: String,
    val website: String,
    val supportEmail: String,
    val scheme: String,
    /** Prefix of the diagnostic console.log lines injected into the Odoo WebView page. */
    val webLogTag: String,
) {
    val websiteLabel: String get() = website.removePrefix("https://")
    val primaryArgb: Long get() = 0xFF000000L or primaryHex.drop(1).toLong(16)
    val isApporo: Boolean get() = code == "apporo"
    val nameResource: Int get() = R.string.app_name
    val supportUrlResource: Int get() = R.string.url_support
    val privacyUrlResource: Int get() = R.string.url_privacy_policy
    val deletionUrlResource: Int get() = R.string.url_account_deletion

    // Apporo: per-channel round(primary * (1-f) + target * f), target black/white.
    // White 85% = EEE9DE / foreground 241C09 (black 74%).
    // Black 45% = 4C3B14 / foreground EEE9DE. Primary/secondary 8B6B24 on white.
    // Legacy WOOW role values are deliberately unchanged.
    val lightContainer: Long get() = if (isApporo) mix(0xFFFFFF, 0.85) else 0xFFDBE1FF
    val darkContainer: Long get() = if (isApporo) mix(0x000000, 0.45) else 0xFF3A4B8C
    val onLightContainer: Long get() = if (isApporo) mix(0x000000, 0.74) else 0xFF001A41
    val onDarkContainer: Long get() = if (isApporo) lightContainer else 0xFFDBE1FF
    val darkPrimary: Long get() = if (isApporo) darkContainer else 0xFF4A6AE0
    val lightPrimary: Long get() = if (isApporo) lightContainer else 0xFF8BA3FF
    val secondary: Long get() = if (isApporo) primaryArgb else 0xFF65C2E0

    /**
     * Container of filled buttons that carry white text. WOOW blue on white is 3.41:1 (below WCAG AA 4.5:1),
     * so WOOW buttons use the same hue (226.8°) and HSL saturation at lightness 0.619 instead of 0.684:
     * 4069FB, 4.53:1. Apporo 8B6B24 is already 4.97:1 and stays the primary.
     */
    val solidButtonArgb: Long get() = if (isApporo) primaryArgb else 0xFF4069FB

    private fun mix(target: Int, fraction: Double): Long {
        val rgb = primaryArgb.toInt()
        var result = 0xFF000000L
        for (shift in listOf(16, 8, 0)) {
            val channel = (((rgb shr shift) and 255) * (1 - fraction) +
                ((target shr shift) and 255) * fraction).roundToInt()
            result = result or (channel.toLong() shl shift)
        }
        return result
    }

    companion object {
        val current: AppBrand = forCode(BuildConfig.APP_BRAND, BuildConfig.DEBUG)

        fun forCode(code: String, debug: Boolean = false): AppBrand = when (code) {
            "woowtech" -> AppBrand(
                code, "#6183FC", "https://aiot.woowtech.io", "woowtech@designsmart.com.tw", "woowodoo",
                "WoowTech"
            )
            "apporo" -> AppBrand(
                code, "#8B6B24", "https://www.apporo.ai", "info@apporo.ai",
                if (debug) "apporoodoo-dev" else "apporoodoo", "Apporo"
            )
            else -> error("Unknown app brand")
        }
    }
}
