package io.woowtech.odoo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.woowtech.odoo.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeManager {
    private val _primaryColor = MutableStateFlow(WoowTechBlue)
    val primaryColor: StateFlow<Color> = _primaryColor.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setPrimaryColor(color: Color) {
        _primaryColor.value = color
    }

    fun setPrimaryColorFromHex(hex: String) {
        try {
            val colorInt = android.graphics.Color.parseColor(hex)
            _primaryColor.value = Color(colorInt)
        } catch (e: Exception) {
            // Keep default color if parsing fails
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }
}

// Brand color ratio: White 50%, Gray 20%, Deep Gray 10%, Blue 10%, Accent 5%, Black 5%
private fun createLightColorScheme(primaryColor: Color) = lightColorScheme(
    primary = primaryColor,                          // Blue 10%
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = AccentSkyBlue,                       // Accent 5%
    onSecondary = OnPrimaryLight,
    tertiary = AccentCoral,                          // Accent 5%
    onTertiary = OnPrimaryLight,
    background = BrandWhite,                         // White 50%
    onBackground = BrandDeepGray,                    // Deep Gray 10%
    surface = BrandWhite,                            // White 50%
    onSurface = BrandDeepGray,                       // Deep Gray 10%
    surfaceVariant = BrandLightGray,                 // Gray 20%
    onSurfaceVariant = BrandGray,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorColor,
    onError = Color.White
)

private fun createDarkColorScheme(primaryColor: Color) = darkColorScheme(
    primary = primaryColor,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = AccentSkyBlue,
    onSecondary = OnPrimaryDark,
    tertiary = AccentCoral,
    onTertiary = OnPrimaryDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = ErrorColor,
    onError = Color.White
)

@Composable
fun WoowTechOdooTheme(
    content: @Composable () -> Unit
) {
    val primaryColor by ThemeManager.primaryColor.collectAsStateWithLifecycle()
    val themeMode by ThemeManager.themeMode.collectAsStateWithLifecycle()
    val systemDarkTheme = isSystemInDarkTheme()

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) {
        createDarkColorScheme(primaryColor)
    } else {
        createLightColorScheme(primaryColor)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = primaryColor.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
