package io.woowtech.odoo.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.woowtech.odoo.R

// v1.0.16: Common timezones list
private val COMMON_TIMEZONES = listOf(
    "Asia/Taipei" to "Asia/Taipei (UTC+8)",
    "Asia/Tokyo" to "Asia/Tokyo (UTC+9)",
    "Asia/Shanghai" to "Asia/Shanghai (UTC+8)",
    "Asia/Hong_Kong" to "Asia/Hong Kong (UTC+8)",
    "Asia/Singapore" to "Asia/Singapore (UTC+8)",
    "Asia/Seoul" to "Asia/Seoul (UTC+9)",
    "Asia/Bangkok" to "Asia/Bangkok (UTC+7)",
    "America/New_York" to "America/New York (UTC-5)",
    "America/Los_Angeles" to "America/Los Angeles (UTC-8)",
    "America/Chicago" to "America/Chicago (UTC-6)",
    "Europe/London" to "Europe/London (UTC+0)",
    "Europe/Paris" to "Europe/Paris (UTC+1)",
    "Europe/Berlin" to "Europe/Berlin (UTC+1)",
    "Australia/Sydney" to "Australia/Sydney (UTC+10)",
    "Pacific/Auckland" to "Pacific/Auckland (UTC+12)",
    "UTC" to "UTC (UTC+0)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onEditSignature: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // v1.0.16: Dialog states
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showTimezonePicker by remember { mutableStateOf(false) }
    var showNotificationPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadProfile()
    }

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("Profile updated successfully")
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_details)) },
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
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Avatar
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Full Name
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::updateName,
                    label = { Text(stringResource(R.string.full_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Email (read-only)
                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.email)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = true,
                    enabled = false
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Phone
                OutlinedTextField(
                    value = uiState.phone,
                    onValueChange = viewModel::updatePhone,
                    label = { Text(stringResource(R.string.phone)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Mobile
                OutlinedTextField(
                    value = uiState.mobile,
                    onValueChange = viewModel::updateMobile,
                    label = { Text(stringResource(R.string.mobile)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Website
                OutlinedTextField(
                    value = uiState.website,
                    onValueChange = viewModel::updateWebsite,
                    label = { Text(stringResource(R.string.website)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Job Title
                OutlinedTextField(
                    value = uiState.jobTitle,
                    onValueChange = viewModel::updateJobTitle,
                    label = { Text(stringResource(R.string.job_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))

                // v1.0.16: Preferences Section
                PreferencesSection(
                    title = stringResource(R.string.preferences)
                ) {
                    PreferenceItem(
                        icon = Icons.Default.Language,
                        title = stringResource(R.string.language),
                        value = uiState.getLanguageDisplayName(),
                        onClick = { showLanguagePicker = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                    PreferenceItem(
                        icon = Icons.Default.Schedule,
                        title = stringResource(R.string.timezone),
                        value = uiState.tz.ifEmpty { "Not set" },
                        onClick = { showTimezonePicker = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                    PreferenceItem(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.notification),
                        value = uiState.getNotificationDisplayName(),
                        onClick = { showNotificationPicker = true }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // v1.0.16: Personalization Section
                PreferencesSection(
                    title = stringResource(R.string.personalization)
                ) {
                    PreferenceItem(
                        icon = Icons.Default.Edit,
                        title = stringResource(R.string.email_signature),
                        value = if (uiState.signature.isNotBlank()) {
                            stringResource(R.string.signature_set)
                        } else {
                            stringResource(R.string.signature_not_set)
                        },
                        onClick = onEditSignature
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Save button
                Button(
                    onClick = { viewModel.saveProfile() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !uiState.isSaving && uiState.hasChanges
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.save))
                    }
                }

                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    // v1.0.16: Language Picker Dialog
    if (showLanguagePicker) {
        LanguagePickerDialog(
            languages = uiState.availableLanguages,
            currentLang = uiState.lang,
            onLanguageSelected = {
                viewModel.updateLanguage(it)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false }
        )
    }

    // v1.0.16: Timezone Picker Dialog
    if (showTimezonePicker) {
        TimezonePickerDialog(
            currentTz = uiState.tz,
            onTimezoneSelected = {
                viewModel.updateTimezone(it)
                showTimezonePicker = false
            },
            onDismiss = { showTimezonePicker = false }
        )
    }

    // v1.0.16: Notification Type Picker Dialog
    if (showNotificationPicker) {
        NotificationPickerDialog(
            currentType = uiState.notificationType,
            onTypeSelected = {
                viewModel.updateNotificationType(it)
                showNotificationPicker = false
            },
            onDismiss = { showNotificationPicker = false }
        )
    }
}

// v1.0.16: Preferences Section composable
@Composable
private fun PreferencesSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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

// v1.0.16: Preference Item composable
@Composable
private fun PreferenceItem(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// v1.0.16: Language Picker Dialog
@Composable
private fun LanguagePickerDialog(
    languages: List<io.woowtech.odoo.domain.model.OdooLanguage>,
    currentLang: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_language)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                languages.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(language.code) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = language.code == currentLang,
                            onClick = { onLanguageSelected(language.code) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = language.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (language.code == currentLang) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
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

// v1.0.16: Timezone Picker Dialog
@Composable
private fun TimezonePickerDialog(
    currentTz: String,
    onTimezoneSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_timezone)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                COMMON_TIMEZONES.forEach { (code, displayName) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTimezoneSelected(code) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = code == currentTz,
                            onClick = { onTimezoneSelected(code) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (code == currentTz) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
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

// v1.0.16: Notification Type Picker Dialog
@Composable
private fun NotificationPickerDialog(
    currentType: String,
    onTypeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "email" to stringResource(R.string.notification_email),
        "inbox" to stringResource(R.string.notification_inbox)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_notification)) },
        text = {
            Column {
                options.forEach { (code, displayName) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTypeSelected(code) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = code == currentType,
                            onClick = { onTypeSelected(code) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (code == currentType) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
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
