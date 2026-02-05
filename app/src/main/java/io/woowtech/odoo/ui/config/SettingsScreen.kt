package io.woowtech.odoo.ui.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showColorPicker by remember { mutableStateOf(false) }
    var showPinSetup by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    val biometricManager = remember { BiometricManager.from(context) }
    val canUseBiometric = remember {
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Security Section
            SettingsSection(title = stringResource(R.string.security)) {
                SettingsToggleItem(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.app_lock),
                    subtitle = stringResource(R.string.app_lock_subtitle),
                    checked = settings.appLockEnabled,
                    onCheckedChange = { viewModel.updateAppLock(it) }
                )

                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                if (canUseBiometric) {
                    SettingsToggleItem(
                        icon = Icons.Default.Fingerprint,
                        title = stringResource(R.string.biometric_unlock),
                        subtitle = stringResource(R.string.biometric_unlock_subtitle),
                        checked = settings.biometricEnabled,
                        onCheckedChange = { viewModel.updateBiometric(it) },
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
                        viewModel.clearCache(context)
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
            onLanguageSelected = {
                viewModel.updateLanguage(it)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false }
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
    val presetColors = listOf(
        "#6183FC", // WoowTech Blue
        "#E91E63", // Pink
        "#9C27B0", // Purple
        "#3F51B5", // Indigo
        "#03A9F4", // Light Blue
        "#009688", // Teal
        "#4CAF50", // Green
        "#FF9800", // Orange
        "#795548", // Brown
        "#607D8B"  // Blue Grey
    )

    var selectedColor by remember { mutableStateOf(currentColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_color)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.preset_colors),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Color grid
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    presetColors.chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(android.graphics.Color.parseColor(color)))
                                        .clickable { selectedColor = color }
                                        .then(
                                            if (selectedColor == color) {
                                                Modifier.padding(4.dp)
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                            }
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

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}
