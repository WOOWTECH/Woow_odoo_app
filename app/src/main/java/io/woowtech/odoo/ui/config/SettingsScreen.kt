package io.woowtech.odoo.ui.config

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.biometric.BiometricManager
import io.woowtech.odoo.ui.auth.PinSetupScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.woowtech.odoo.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showColorPicker by remember { mutableStateOf(false) }
    var showThemeModePicker by remember { mutableStateOf(false) }
    var showPinSetup by remember { mutableStateOf(false) }
    // When App Lock is toggled on without a PIN, route through PIN setup first and remember to
    // enable App Lock once the PIN is created (PIN-as-floor invariant).
    var pendingEnableAppLock by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    val biometricManager = remember { BiometricManager.from(context) }
    val canUseBiometric = remember {
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    // WI-0: PIN create/confirm overlay. Shown when setting a PIN or when enabling App Lock (which
    // requires a PIN floor). Full-screen so it replaces the settings list while active.
    if (showPinSetup) {
        PinSetupScreen(
            reduceMotion = settings.reduceMotion,
            onPinConfirmed = { pin ->
                if (pendingEnableAppLock) {
                    viewModel.setPinThenEnableAppLock(pin)
                    pendingEnableAppLock = false
                } else {
                    viewModel.setPin(pin)
                }
                showPinSetup = false
            },
            onCancel = {
                showPinSetup = false
                pendingEnableAppLock = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Appearance Section
            SettingsSection(title = stringResource(R.string.appearance)) {
                SettingsItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.theme_color),
                    subtitle = stringResource(R.string.theme_color_subtitle),
                    onClick = { showColorPicker = true },
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(settings.themeColor)))
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.BrightnessAuto,
                    title = stringResource(R.string.theme_mode),
                    subtitle = stringResource(
                        when (settings.themeMode) {
                            io.woowtech.odoo.domain.model.ThemeMode.SYSTEM -> R.string.theme_mode_system
                            io.woowtech.odoo.domain.model.ThemeMode.LIGHT -> R.string.theme_mode_light
                            io.woowtech.odoo.domain.model.ThemeMode.DARK -> R.string.theme_mode_dark
                        }
                    ),
                    onClick = { showThemeModePicker = true }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                // H1 fix: Reduce Motion toggle — the preference was persisted and consumed
                // by BiometricScreen/PinScreen animation specs, but no UI toggle existed to
                // let users actually enable it. Added here so users with vestibular disorders
                // or motion sensitivity can disable animations.
                SettingsToggleItem(
                    icon = Icons.Default.AccessibilityNew,
                    title = stringResource(R.string.reduce_motion),
                    subtitle = stringResource(R.string.reduce_motion_subtitle),
                    checked = settings.reduceMotion,
                    onCheckedChange = { viewModel.updateReduceMotion(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security Section
            SettingsSection(title = stringResource(R.string.security)) {
                SettingsToggleItem(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.app_lock),
                    subtitle = stringResource(R.string.app_lock_subtitle),
                    checked = settings.appLockEnabled,
                    onCheckedChange = { enable ->
                        if (enable && !settings.pinEnabled) {
                            // App Lock requires a PIN floor — create one first, then enable.
                            pendingEnableAppLock = true
                            showPinSetup = true
                        } else {
                            viewModel.updateAppLock(enable)
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                if (canUseBiometric) {
                    SettingsToggleItem(
                        icon = Icons.Default.Fingerprint,
                        title = stringResource(R.string.biometric_unlock),
                        subtitle = stringResource(R.string.biometric_unlock_subtitle),
                        checked = settings.biometricEnabled,
                        onCheckedChange = { viewModel.updateBiometric(enabled = it, canUseBiometric = canUseBiometric) },
                        enabled = settings.appLockEnabled
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }

                SettingsItem(
                    icon = Icons.Default.Pin,
                    title = stringResource(R.string.pin_code),
                    subtitle = stringResource(R.string.pin_code_subtitle),
                    onClick = { showPinSetup = true },
                    enabled = settings.appLockEnabled,
                    trailing = {
                        Text(
                            text = if (settings.pinEnabled) {
                                stringResource(R.string.change_pin)
                            } else {
                                stringResource(R.string.set_pin)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy Section
            SettingsSection(title = stringResource(R.string.settings_location_section)) {
                SettingsToggleItem(
                    icon = Icons.Default.MyLocation,
                    title = stringResource(R.string.settings_location_title),
                    subtitle = stringResource(R.string.settings_location_description),
                    checked = settings.locationEnabled,
                    onCheckedChange = { viewModel.updateLocationEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Language & Region Section
            SettingsSection(title = stringResource(R.string.language_region)) {
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.language),
                    subtitle = settings.language.displayName,
                    onClick = { showLanguagePicker = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Data & Storage Section
            SettingsSection(title = stringResource(R.string.data_storage)) {
                SettingsItem(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.clear_cache),
                    subtitle = stringResource(R.string.clear_cache_subtitle),
                    onClick = {
                        viewModel.clearCache()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.cache_cleared)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Help & Support Section
            SettingsSection(title = stringResource(R.string.help_support)) {
                SettingsItem(
                    icon = Icons.AutoMirrored.Filled.HelpCenter,
                    title = stringResource(R.string.odoo_help_center),
                    subtitle = stringResource(R.string.odoo_help_center_subtitle),
                    onClick = { openUrl(context, "https://www.odoo.com/help") }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Forum,
                    title = stringResource(R.string.odoo_community_forum),
                    subtitle = stringResource(R.string.odoo_community_forum_subtitle),
                    onClick = { openUrl(context, "https://www.odoo.com/forum") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About Section
            SettingsSection(title = stringResource(R.string.about)) {
                SettingsItem(
                    icon = Icons.Default.Public,
                    title = stringResource(R.string.visit_website),
                    subtitle = "aiot.woowtech.io",
                    onClick = { openUrl(context, "https://aiot.woowtech.io") }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Email,
                    title = stringResource(R.string.contact_us),
                    subtitle = "woowtech@designsmart.com.tw",
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:woowtech@designsmart.com.tw")
                        }
                        context.startActivity(intent)
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.app_version_title),
                    subtitle = stringResource(R.string.app_version),
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Copyright
            Text(
                text = stringResource(R.string.copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Color Picker Dialog
    if (showColorPicker) {
        ColorPickerDialog(
            currentColor = settings.themeColor,
            onColorSelected = {
                viewModel.updateThemeColor(it)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }

    // Language Picker Dialog
    if (showLanguagePicker) {
        LanguagePickerDialog(
            currentLanguage = settings.language,
            onLanguageSelected = { selectedLanguage ->
                viewModel.updateLanguage(selectedLanguage)
                showLanguagePicker = false
                // H2 fix: Apply the locale change immediately so the UI reflects the
                // new language without requiring an app restart. On API 33+, the OS
                // handles resource reloading. On older APIs, recreating the activity is
                // the only reliable path.
                applyLocaleChange(context = context, languageCode = selectedLanguage.code)
            },
            onDismiss = { showLanguagePicker = false }
        )
    }

    // Theme Mode Picker Dialog
    if (showThemeModePicker) {
        ThemeModePickerDialog(
            currentThemeMode = settings.themeMode,
            onThemeModeSelected = {
                viewModel.updateThemeMode(it)
                showThemeModePicker = false
            },
            onDismiss = { showThemeModePicker = false }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
        }

        trailing?.invoke() ?: Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun ColorPickerDialog(
    currentColor: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Brand colors (from brand guide)
    val brandColors = listOf(
        "#6183FC", // Primary Blue
        "#FFFFFF", // White
        "#EFF1F5", // Light Gray
        "#646262", // Gray
        "#212121"  // Deep Gray
    )

    // Brand accent colors (10 from brand guide)
    val accentColors = listOf(
        "#7BDBE0", // Cyan
        "#F8D158", // Yellow
        "#65C2E0", // Sky Blue
        "#6791DE", // Royal Blue
        "#8CD37F", // Green
        "#B17148", // Brown
        "#F1C692", // Sand
        "#E66D3E", // Orange
        "#F45D6D", // Coral
        "#C09FE0"  // Lavender
    )

    var selectedColor by remember { mutableStateOf(currentColor) }
    var customHex by remember { mutableStateOf("") }
    var hexError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_color)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Brand Colors section
                Text(
                    text = stringResource(R.string.preset_colors),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    items(brandColors) { color ->
                        ColorSwatch(
                            colorHex = color,
                            isSelected = selectedColor.equals(color, ignoreCase = true),
                            onClick = { selectedColor = color }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Accent Colors section
                Text(
                    text = "Accent",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(88.dp)
                ) {
                    items(accentColors) { color ->
                        ColorSwatch(
                            colorHex = color,
                            isSelected = selectedColor.equals(color, ignoreCase = true),
                            onClick = { selectedColor = color }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom HEX input
                Text(
                    text = stringResource(R.string.custom_color),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customHex,
                        onValueChange = { input ->
                            customHex = input.uppercase().filter { it in "0123456789ABCDEF#" }
                            hexError = false
                        },
                        label = { Text("#RRGGBB") },
                        singleLine = true,
                        isError = hexError,
                        modifier = Modifier.weight(1f)
                    )

                    // Preview swatch of custom color
                    if (customHex.length >= 7) {
                        val previewHex = if (customHex.startsWith("#")) customHex else "#$customHex"
                        val parsedColor = runCatching {
                            Color(android.graphics.Color.parseColor(previewHex))
                        }.getOrNull()

                        if (parsedColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(parsedColor)
                                    .clickable {
                                        selectedColor = previewHex
                                    }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(selectedColor) }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ColorSwatch(
    colorHex: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(android.graphics.Color.parseColor(colorHex)))
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
                }
            )
            .clickable { onClick() }
    )
}

@Composable
private fun LanguagePickerDialog(
    currentLanguage: io.woowtech.odoo.domain.model.AppLanguage,
    onLanguageSelected: (io.woowtech.odoo.domain.model.AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                io.woowtech.odoo.domain.model.AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(language) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = language.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (language == currentLanguage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )

                        if (language == currentLanguage) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ThemeModePickerDialog(
    currentThemeMode: io.woowtech.odoo.domain.model.ThemeMode,
    onThemeModeSelected: (io.woowtech.odoo.domain.model.ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_mode)) },
        text = {
            Column {
                io.woowtech.odoo.domain.model.ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeModeSelected(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                when (mode) {
                                    io.woowtech.odoo.domain.model.ThemeMode.SYSTEM -> R.string.theme_mode_system
                                    io.woowtech.odoo.domain.model.ThemeMode.LIGHT -> R.string.theme_mode_light
                                    io.woowtech.odoo.domain.model.ThemeMode.DARK -> R.string.theme_mode_dark
                                }
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (mode == currentThemeMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )

                        if (mode == currentThemeMode) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Applies the selected [languageCode] as the active per-app locale immediately.
 *
 * On API 33 and above, [LocaleManager.setApplicationLocales] persists the preference at
 * the OS level without restarting the activity — the system handles resource reloading.
 * The special value [io.woowtech.odoo.domain.model.AppLanguage.SYSTEM] uses code "system"
 * which maps to an empty [LocaleList], restoring the device default.
 *
 * On API 32 and below, [Activity.recreate] is the only fully reliable approach; it
 * re-inflates all string resources from the updated [android.content.res.Configuration].
 * Calling recreate on a non-Activity context is silently ignored.
 */
private fun applyLocaleChange(context: Context, languageCode: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager?.applicationLocales = if (languageCode == "system") {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(languageCode)
        }
    } else {
        (context as? Activity)?.recreate()
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}
