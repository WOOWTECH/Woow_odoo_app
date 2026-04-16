package io.woowtech.odoo.ui.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    // C1 fix: read reduceMotion once per composition; individual animation specs reference
    // this val so changes to the setting are reflected on the next recompose.
    val reduceMotion = settings.reduceMotion
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // L7: Start false and let the LaunchedEffect determine the real value on the first tick.
    // Previously `viewModel.isLockedOut()` was called at composition time, which could race
    // with the lockout countdown coroutine resuming after a bg→fg transition — the persisted
    // lockout could expire in the gap between the `isLockedOut()` call and the first
    // `getLockoutRemainingMs()` check inside the effect, leaving the screen incorrectly
    // locked for up to one 500ms tick. Initialising false and letting the effect correct
    // it immediately removes this race.
    var isLockedOut by remember { mutableStateOf(false) }
    var isShaking by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current

    // FLAG_SECURE is set globally in MainActivity.onCreate (L6 fix) — no per-screen
    // DisposableEffect is needed here. Removing it eliminates the rotation gap that would
    // briefly clear FLAG_SECURE between DisposableEffect teardown and re-composition.

    // Lockout countdown — keyed on lifecycleOwner so the coroutine is automatically
    // cancelled when the Composable leaves composition (screen navigated away) or when the
    // lifecycle owner changes. Evaluates the initial lockout state on the first tick,
    // then polls every 500 ms while locked out. 500 ms gives sub-second visual accuracy
    // without burning CPU. The LaunchedEffect coroutine is cancelled by Compose when the
    // screen leaves the active composition. (L7 + C3 fix)
    LaunchedEffect(lifecycleOwner) {
        // Re-evaluate every time the lifecycle restarts — covers bg/fg transitions and
        // the initial composition. Reading getLockoutRemainingMs() on the first tick ensures
        // isLockedOut is set from persisted state before the first frame is rendered.
        val initialRemaining = viewModel.getLockoutRemainingMs()
        isLockedOut = initialRemaining > 0
        while (isLockedOut && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            val remainingMs = viewModel.getLockoutRemainingMs()
            if (remainingMs <= 0) {
                isLockedOut = false
                break
            }
            delay(500)
        }
    }

    // C1 fix: Shake animation respects reduceMotion. When reduceMotion=true, snap() gives
    // an instant jump rather than the lateral shake movement, eliminating motion for users
    // who have opted out of animations.
    val shakeOffset by animateFloatAsState(
        targetValue = if (isShaking) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(100),
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
                    reduceMotion = reduceMotion,
                    onNumberClick = { number ->
                        if (pin.length < 6) {
                            error = null
                            val (nextPin, result) = viewModel.enterPinDigit(number, pin)
                            pin = nextPin
                            when (result) {
                                is PinEntryResult.NeedMoreDigits -> {
                                    // Keep accumulating — stored PIN may be 5 or 6 digits
                                }
                                is PinEntryResult.Success -> onPinVerified()
                                is PinEntryResult.WrongPin -> {
                                    error = context.getString(
                                        R.string.wrong_pin_attempts_remaining,
                                        result.remainingAttempts
                                    )
                                    isShaking = true
                                }
                                is PinEntryResult.LockedOut -> {
                                    isLockedOut = true
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
    reduceMotion: Boolean,
    onNumberClick: (String) -> Unit,
    onDeleteClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Row 1: 1, 2, 3
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            NumberKey(number = "1", reduceMotion = reduceMotion, onClick = onNumberClick)
            NumberKey(number = "2", reduceMotion = reduceMotion, onClick = onNumberClick)
            NumberKey(number = "3", reduceMotion = reduceMotion, onClick = onNumberClick)
        }
        // Row 2: 4, 5, 6
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            NumberKey(number = "4", reduceMotion = reduceMotion, onClick = onNumberClick)
            NumberKey(number = "5", reduceMotion = reduceMotion, onClick = onNumberClick)
            NumberKey(number = "6", reduceMotion = reduceMotion, onClick = onNumberClick)
        }
        // Row 3: 7, 8, 9
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            NumberKey(number = "7", reduceMotion = reduceMotion, onClick = onNumberClick)
            NumberKey(number = "8", reduceMotion = reduceMotion, onClick = onNumberClick)
            NumberKey(number = "9", reduceMotion = reduceMotion, onClick = onNumberClick)
        }
        // Row 4: empty, 0, backspace
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Spacer(modifier = Modifier.size(76.dp))
            NumberKey(number = "0", reduceMotion = reduceMotion, onClick = onNumberClick)
            DeleteKey(reduceMotion = reduceMotion, onClick = onDeleteClick)
        }
    }
}

@Composable
private fun NumberKey(
    number: String,
    reduceMotion: Boolean,
    onClick: (String) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // C1 fix: key-press scale animation respects reduceMotion.
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(100),
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
private fun DeleteKey(reduceMotion: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // C1 fix: delete-key press scale animation respects reduceMotion.
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(100),
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
