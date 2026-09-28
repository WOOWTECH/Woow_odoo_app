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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.woowtech.odoo.R
import io.woowtech.odoo.data.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.woowtech.odoo.ui.theme.WoowFixedBrandTheme

/** Dots in the PIN row — one per digit of the fixed-length PIN. */
private const val PIN_DOT_COUNT = SettingsRepository.PIN_LENGTH

/** Horizontal amplitude (dp) of the wrong-PIN shake displacement. */
private const val SHAKE_AMPLITUDE_DP = 10

/** Whole seconds shown for [remainingMs] of lockout, rounded up so "1" stays until the lockout ends. */
internal fun lockoutSecondsLeft(remainingMs: Long): Int =
    if (remainingMs <= 0) 0 else ((remainingMs + 999) / 1000).toInt()

/** Delay until the shown second changes (0 < result <= 1000 while locked out). */
internal fun msUntilLockoutSecondChanges(remainingMs: Long): Long =
    remainingMs - (lockoutSecondsLeft(remainingMs) - 1) * 1000L

@Composable
fun PinScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onPinVerified: () -> Unit,
    onBackClick: () -> Unit,
    // Hidden when PIN is the sole unlock gate (nowhere to go back to); shown when reached from the
    // biometric screen's "Use PIN" so the user can return to the face prompt.
    showBack: Boolean = true,
    // Unlock wording by default; pin_code_subtitle is the Settings row's "set up" description.
    subtitle: String = stringResource(R.string.enter_pin_subtitle),
    // Verification of each keypad digit. The unlock gate authenticates the session; Settings reuses
    // this keypad to confirm turning App Lock off (same check, counter and lockout).
    enterPinDigit: suspend (digit: String, currentPin: String) -> Pair<String, PinEntryResult> = viewModel::enterPinDigit,
) {
    WoowFixedBrandTheme {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    // Read reduceMotion once per composition; individual animation specs reference
    // this val so changes to the setting are reflected on the next recompose.
    val reduceMotion = settings.reduceMotion
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Seconds left on the PIN lockout; > 0 hides the keypad and shows the countdown.
    // L7: Start at 0 and let the LaunchedEffect determine the real value on the first tick, so a
    // lockout that expires between composition and the first check can't keep the screen locked.
    var lockoutSeconds by remember { mutableIntStateOf(0) }
    val isLockedOut = lockoutSeconds > 0
    // Bumped on every LockedOut result so a lockout started on this screen always (re)starts the
    // countdown, even when the settings emission carrying the new expiry lands a frame later.
    var lockoutEpoch by remember { mutableIntStateOf(0) }
    var isShaking by remember { mutableStateOf(false) }
    // ANR fix: verifyPin runs PBKDF2 (600K iterations) off the main thread via
    // Dispatchers.Default. isVerifying gates rapid taps so only one verify is in
    // flight at a time, and drives the CircularProgressIndicator next to the PIN dots.
    var isVerifying by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current

    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val lockoutUntil = settings.pinLockoutUntil

    // Lockout countdown, driven by the observable lockout expiry: it (re)starts whenever the
    // persisted expiry changes, whenever this screen gets a LockedOut result ([lockoutEpoch]) and on
    // every return to the foreground, and ticks once per shown second until the lockout ends — then
    // the keypad comes back by itself. Previously this effect was keyed on the lifecycle owner only,
    // so a lockout started while the screen was open (the 5th wrong PIN) never cleared: the unlock
    // gate, which has no back button, stayed without a keypad (verify-20260928 attempt2-31..34).
    // Paused while stopped; cancelled when the screen leaves composition.
    LaunchedEffect(lockoutUntil, lockoutEpoch, isStarted) {
        if (!isStarted) return@LaunchedEffect
        while (true) {
            val remainingMs = viewModel.getLockoutRemainingMs()
            lockoutSeconds = lockoutSecondsLeft(remainingMs)
            if (lockoutSeconds == 0) break
            delay(msUntilLockoutSecondChanges(remainingMs))
        }
    }

    // Shake animation for wrong PIN — respects reduceMotion. When reduceMotion=true,
    // snap() gives an instant jump rather than the lateral shake movement, eliminating
    // motion for users who have opted out of animations.
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
        // MainActivity 開了 enableEdgeToEdge()。有 TopAppBar／Scaffold 的畫面會自動避開系統列，
        // 這個畫面沒有，要自己吃 safeDrawing（狀態列、導覽列、瀏海），否則左上角返回鍵會被畫在
        // 狀態列底下而點不到（同 LoginScreen 29bb12f）。背景漸層仍鋪滿到螢幕邊緣（在外層 Box）。
        // 扣掉系統列後短視窗（如 360×640dp）放不下 4 列 76dp 鍵盤，第四列（0／刪除）會被壓成 0 高度，
        // 所以 inset 之後可捲動。verticalScroll 保留 fillMaxSize 給的最小高度，長視窗 weight spacer
        // 照舊把鍵盤推到底部，版面不變（PinPadShortWindowTest）。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Back button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                if (showBack) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // PIN dots with better visibility + verifying indicator
            PinDotsRow(
                filledCount = pin.length,
                shakeOffset = shakeOffset,
                reduceMotion = reduceMotion,
                isVerifying = isVerifying,
            )

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
                        // iOS `lockout_timer_%lld` parity: the remaining seconds, updated every second.
                        text = context.resources.getQuantityString(
                            R.plurals.pin_lockout_countdown, lockoutSeconds, lockoutSeconds
                        ),
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
                        // Guard against rapid taps while PBKDF2 is running off-thread.
                        if (isVerifying) return@NumberPad
                        if (pin.length < 6) {
                            error = null
                            val previousPin = pin
                            // Show the digit at once: the 6th dot must be filled while that entry
                            // is verified (PBKDF2 takes seconds on device), not only afterwards.
                            pin = previousPin + number
                            // Set before launching so a second tap in the same frame is ignored.
                            isVerifying = true
                            scope.launch {
                                val (nextPin, result) = enterPinDigit(number, previousPin)
                                isVerifying = false
                                pin = nextPin
                                when (result) {
                                    is PinEntryResult.NeedMoreDigits -> {
                                        // Keep accumulating — stored PIN may be 5 or 6 digits
                                    }
                                    is PinEntryResult.Success -> onPinVerified()
                                    is PinEntryResult.WrongPin -> {
                                        error = context.resources.getQuantityString(
                                            R.plurals.wrong_pin_attempts_remaining,
                                            result.remainingAttempts,
                                            result.remainingAttempts
                                        )
                                        isShaking = true
                                    }
                                    is PinEntryResult.LockedOut -> {
                                        // The effect above reads the new expiry and counts it down.
                                        lockoutEpoch++
                                    }
                                }
                            }
                        }
                    },
                    onDeleteClick = {
                        // Same guard as digits: the entry being verified stays as typed.
                        if (!isVerifying && pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
    }
}

/**
 * The row of six PIN dots (filled = entered digits) plus the PBKDF2 "verifying" spinner, including
 * the wrong-PIN shake displacement. Stateless and hoisted out of [PinScreen] so the shake can be
 * rendered deterministically at a fixed [shakeOffset] in a unit test — the shake path is where the
 * crash lived (a negative padding value).
 *
 * @param filledCount number of dots to render filled (the current PIN length)
 * @param shakeOffset horizontal shake progress in [0f, 1f]; 0 at rest
 * @param reduceMotion when true, no lateral movement is applied (accessibility opt-out)
 * @param isVerifying when true, shows the spinner next to the dots
 */
@Composable
internal fun PinDotsRow(
    filledCount: Int,
    shakeOffset: Float,
    reduceMotion: Boolean,
    isVerifying: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(vertical = 16.dp)
            // Wrong-PIN shake: translate the row horizontally at placement time. Using offset (not
            // padding) is what fixes the crash — padding requires non-negative values, but the shake
            // needs a signed displacement. offset also avoids re-measuring or reflowing siblings.
            // reduceMotion users get no lateral movement at all.
            .offset(x = if (reduceMotion) 0.dp else (shakeOffset * SHAKE_AMPLITUDE_DP).dp)
            // "n/6" for accessibility services (iOS accessibilityValue parity) and tests.
            .semantics { stateDescription = "$filledCount/$PIN_DOT_COUNT" }
    ) {
        repeat(PIN_DOT_COUNT) { index ->
            val isFilled = index < filledCount
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
            if (index < PIN_DOT_COUNT - 1) Spacer(modifier = Modifier.width(16.dp))
        }
        // Show a small spinner next to the dots while PBKDF2 is running. A 200ms debounce is
        // applied via isVerifying so quick digit taps that don't reach the verify threshold
        // never flash the indicator.
        if (isVerifying) {
            Spacer(modifier = Modifier.width(12.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Reusable numeric keypad. Shared by [PinScreen] (verify) and [PinSetupScreen] (create). */
@Composable
internal fun NumberPad(
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
    // Key-press scale animation respects reduceMotion.
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
    // Delete-key press scale animation respects reduceMotion.
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
