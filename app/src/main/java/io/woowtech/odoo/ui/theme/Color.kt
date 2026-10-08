package io.woowtech.odoo.ui.theme

import io.woowtech.odoo.brand.AppBrand
import androidx.compose.ui.graphics.Color

// ─── Brand Colors (from woowtech_claude_brand_prompt_library.pdf) ───

val BrandPrimaryBlue = Color(AppBrand.current.primaryArgb)
/** Filled-button container for the brand primary (white text ≥ 4.5:1), see [AppBrand.solidButtonArgb]. */
val BrandSolidButton = Color(AppBrand.current.solidButtonArgb)
val BrandWhite = Color(0xFFFFFFFF)
val BrandLightGray = Color(0xFFEFF1F5)
val BrandGray = Color(0xFF646262)
val BrandDeepGray = Color(0xFF212121)

// ─── Brand Accent Colors (10 brand-defined accents) ───

val AccentCyan = Color(0xFF7BDBE0)
val AccentYellow = Color(0xFFF8D158)
val AccentSkyBlue = Color(0xFF65C2E0)
val AccentRoyalBlue = Color(0xFF6791DE)
val AccentGreen = Color(0xFF8CD37F)
val AccentBrown = Color(0xFFB17148)
val AccentSand = Color(0xFFF1C692)
val AccentOrange = Color(0xFFE66D3E)
val AccentCoral = Color(0xFFF45D6D)
val AccentLavender = Color(0xFFC09FE0)

// ─── Legacy aliases (keep for existing code references) ───

val WoowTechBlue = BrandPrimaryBlue
val WoowTechBlueDark = Color(AppBrand.current.darkPrimary)
val WoowTechBlueLight = Color(AppBrand.current.lightPrimary)

// ─── Surface colors ───

val SurfaceLight = BrandWhite
val SurfaceDark = Color(0xFF1C1B1F)
val BackgroundLight = BrandLightGray
val BackgroundDark = Color(0xFF121212)

// ─── Text colors ───

val TextPrimaryLight = BrandDeepGray
val TextPrimaryDark = Color(0xFFE1E1E1)
val TextSecondaryLight = BrandGray
val TextSecondaryDark = Color(0xFFB3B3B3)

// ─── Status colors ───

val ErrorColor = Color(0xFFD32F2F)
/**
 * Dark-theme error (field labels and messages on the login form). #D32F2F on the dark background
 * #121212 is only 3.76:1 (3.44:1 on surface #1C1B1F); the same hue and saturation lightened from
 * 0.506 to 0.602 gives #DC5757: 4.95:1 on background, 4.52:1 on surface.
 */
val ErrorColorDark = Color(0xFFDC5757)
/** Text on [ErrorColorDark]: white on it is only 3.79:1, the dark background colour is 4.95:1. */
val OnErrorDark = Color(0xFF121212)
val SuccessColor = Color(0xFF388E3C)
val WarningColor = Color(0xFFF57C00)
val InfoColor = Color(0xFF1976D2)

// ─── On colors ───

val OnPrimaryLight = Color(0xFFFFFFFF)
val OnPrimaryDark = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1C1B1F)
val OnSurfaceDark = Color(0xFFE6E1E5)
val OnBackgroundLight = Color(0xFF1C1B1F)
val OnBackgroundDark = Color(0xFFE6E1E5)

// ─── Container colors ───

val PrimaryContainerLight = Color(AppBrand.current.lightContainer)
val PrimaryContainerDark = Color(AppBrand.current.darkContainer)
val OnPrimaryContainerLight = Color(AppBrand.current.onLightContainer)
val OnPrimaryContainerDark = Color(AppBrand.current.onDarkContainer)

// ─── Outline colors ───

val OutlineLight = Color(0xFF79747E)
val OutlineDark = Color(0xFF938F99)
val OutlineVariantLight = Color(0xFFCAC4D0)
val OutlineVariantDark = Color(0xFF49454F)

// ─── PIN/Input element colors ───

val PinDotEmptyLight = Color(0xFFB0B0B0)
val PinDotEmptyDark = Color(0xFF6B6B6B)
val PinDotFilledLight = BrandPrimaryBlue
val PinDotFilledDark = Color(AppBrand.current.lightPrimary)

// ─── Number pad colors ───

val NumberPadBorderLight = Color(0xFF9E9E9E)
val NumberPadBorderDark = Color(0xFF757575)
val NumberPadBackgroundLight = BrandLightGray
val NumberPadBackgroundDark = Color(0xFF2A2A2A)
