package io.woowtech.odoo.ui.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.woowtech.odoo.R
import kotlinx.coroutines.delay

@Composable
fun PinScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onPinVerified: () -> Unit,
    onBackClick: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLockedOut by remember { mutableStateOf(viewModel.isLockedOut()) }
    var isShaking by remember { mutableStateOf(false) }

    LaunchedEffect(isLockedOut) {
        if (isLockedOut) {
            while (viewModel.isLockedOut()) {
                delay(1000)
            }
            isLockedOut = false
        }
    }

    // Shake animation for wrong PIN
    val shakeOffset by animateFloatAsState(
        targetValue = if (isShaking) 1f else 0f,
        animationSpec = tween(100),
        finishedListener = { isShaking = false },
        label = "shake"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Back button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back_button),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Title with better styling
            Text(
                text = stringResource(R.string.enter_pin),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.pin_code_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // PIN dots with better visibility
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .then(
                        if (shakeOffset > 0) Modifier.padding(
                            start = (shakeOffset * 10).dp,
                            end = (-shakeOffset * 10).dp
                        ) else Modifier
                    )
            ) {
                repeat(6) { index ->
                    val isFilled = index < pin.length
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                if (isFilled) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .border(
                                width = 2.dp,
                                color = if (isFilled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                    )
                    if (index < 5) Spacer(modifier = Modifier.width(16.dp))
                }
            }

            // Error message with surface container
            error?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (isLockedOut) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text(
                        text = stringResource(R.string.try_again_later),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Number pad with improved visibility
            if (!isLockedOut) {
                NumberPad(
                    onNumberClick = { number ->
                        if (pin.length < 6) {
                            pin += number
                            error = null

                            if (pin.length >= 4) {
                                if (viewModel.verifyPin(pin)) {
                                    onPinVerified()
                                } else {
                                    val remaining = viewModel.getRemainingAttempts()
                                    if (remaining > 0) {
                                        error = "Wrong PIN. $remaining attempts remaining"
                                        isShaking = true
                                    } else {
                                        isLockedOut = true
                                    }
                                    pin = ""
                                }
                            }
                        }
                    },
                    onDeleteClick = {
                        if (pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun NumberPad(
    onNumberClick: (String) -> Unit,
    onDeleteClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Row 1: 1, 2, 3
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            NumberKey("1", onNumberClick)
            NumberKey("2", onNumberClick)
            NumberKey("3", onNumberClick)
        }
        // Row 2: 4, 5, 6
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            NumberKey("4", onNumberClick)
            NumberKey("5", onNumberClick)
            NumberKey("6", onNumberClick)
        }
        // Row 3: 7, 8, 9
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            NumberKey("7", onNumberClick)
            NumberKey("8", onNumberClick)
            NumberKey("9", onNumberClick)
        }
        // Row 4: empty, 0, backspace
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Spacer(modifier = Modifier.size(76.dp))
            NumberKey("0", onNumberClick)
            DeleteKey(onDeleteClick)
        }
    }
}

@Composable
private fun NumberKey(
    number: String,
    onClick: (String) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "keyScale"
    )

    Box(
        modifier = Modifier
            .size(76.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                color = if (isPressed)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick(number) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DeleteKey(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "deleteScale"
    )

    Box(
        modifier = Modifier
            .size(76.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                color = if (isPressed)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else
                    Color.Transparent
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Delete",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp)
        )
    }
}
