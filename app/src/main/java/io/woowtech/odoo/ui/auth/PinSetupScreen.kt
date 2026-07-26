package io.woowtech.odoo.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.SettingsRepository

private enum class PinSetupStage { ENTER, CONFIRM }

/**
 * Create-and-confirm PIN flow (story `story-android-applock-deadlock-fix`, WI-0).
 *
 * Two stages: ENTER a 6-digit PIN, then CONFIRM it by re-entering. The PIN is handed to
 * [onPinConfirmed] only when both entries match; a mismatch resets to ENTER with an error. This is
 * the production PIN-creation UI that previously did not exist (the old Settings "Set PIN" button
 * flipped a flag nothing rendered), and it is the foundation of the "PIN-as-floor" App Lock model.
 *
 * Pure UI: it performs no storage itself. The caller persists the confirmed PIN (e.g. via
 * `SettingsViewModel.setPin`). [onCancel] backs out without setting a PIN.
 */
@Composable
fun PinSetupScreen(
    reduceMotion: Boolean = false,
    onPinConfirmed: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(PinSetupStage.ENTER) }
    var firstPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun resetToEnter() {
        stage = PinSetupStage.ENTER
        firstPin = ""
        pin = ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = stringResource(
                    if (stage == PinSetupStage.ENTER) R.string.pin_setup_title else R.string.pin_setup_confirm_title,
                ),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(
                    if (stage == PinSetupStage.ENTER) R.string.pin_setup_subtitle else R.string.pin_setup_confirm_subtitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(40.dp))

            // PIN dots (MAX_PIN_LENGTH slots, filled by current entry length).
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                repeat(SettingsRepository.PIN_LENGTH) { index ->
                    val filled = index < pin.length
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (filled) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(
                                width = 2.dp,
                                color = if (filled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                },
                                shape = CircleShape,
                            ),
                    )
                    if (index < SettingsRepository.PIN_LENGTH - 1) Spacer(modifier = Modifier.width(16.dp))
                }
            }

            error?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.9f),
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            NumberPad(
                reduceMotion = reduceMotion,
                onNumberClick = { digit ->
                    error = null
                    if (pin.length < SettingsRepository.PIN_LENGTH) {
                        pin += digit
                        // Fixed 6-digit PIN: act only when all 6 are entered — no "Next" button, no
                        // ambiguous partial-length handling. ENTER auto-advances to CONFIRM; CONFIRM
                        // auto-submits on match.
                        if (pin.length == SettingsRepository.PIN_LENGTH) {
                            when (stage) {
                                PinSetupStage.ENTER -> {
                                    firstPin = pin
                                    pin = ""
                                    stage = PinSetupStage.CONFIRM
                                }
                                PinSetupStage.CONFIRM -> {
                                    if (pin == firstPin) {
                                        onPinConfirmed(pin)
                                    } else {
                                        error = context.getString(R.string.pin_setup_mismatch)
                                        resetToEnter()
                                    }
                                }
                            }
                        }
                    }
                },
                onDeleteClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
