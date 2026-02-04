package io.woowtech.odoo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeManager {
    private val _primaryColor = MutableStateFlow(WoowTechBlue)
    val primaryColor: StateFlow<Color> = _primaryColor.asStateFlow()

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
}

private fun createLightColorScheme(primaryColor: Color) = lightColorScheme(
    primary = primaryColor,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = primaryColor.copy(alpha = 0.7f),
    onSecondary = OnPrimaryLight,
    tertiary = primaryColor.copy(alpha = 0.5f),
    onTertiary = OnPrimaryLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = BackgroundLight,
    onSurfaceVariant = TextSecondaryLight,
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
    secondary = primaryColor.copy(alpha = 0.7f),
    onSecondary = OnPrimaryDark,
    tertiary = primaryColor.copy(alpha = 0.5f),
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val primaryColor by ThemeManager.primaryColor.collectAsState()

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
