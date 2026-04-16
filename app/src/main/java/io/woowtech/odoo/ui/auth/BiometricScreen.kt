package io.woowtech.odoo.ui.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import io.woowtech.odoo.R

/**
 * Biometric authentication screen. There is no "skip" path — if the user cannot or will
 * not authenticate with biometrics and has a PIN configured they are routed to [PinScreen].
 * If neither biometric nor PIN is available the caller is responsible for routing the user
 * to PIN setup before arriving at this screen.
 *
 * FLAG_SECURE is applied at the window level in MainActivity.onCreate (L6 fix), so no
 * per-screen DisposableEffect is needed here.
 */
@Composable
fun BiometricScreen(
    viewModel: AuthViewModel = hiltViewModel(),
    onAuthSuccess: () -> Unit,
    onUsePinClick: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failureCount by remember { mutableIntStateOf(0) }
    var isAnimating by remember { mutableStateOf(false) }

    val maxFailures = AuthViewModel.MAX_BIOMETRIC_FAILURES

    // Helper is only valid when hosted by a FragmentActivity — which MainActivity is.
    // If somehow not (preview/tests), treat biometric as unavailable.
    val activity = context as? FragmentActivity
    val biometricHelper = remember(activity) {
        activity?.let { BiometricPromptHelper(it, ContextCompat.getMainExecutor(context)) }
    }
    val canUseBiometric = remember(biometricHelper) {
        biometricHelper?.canAuthenticate() == BiometricAvailability.Available
    }

    // Respect the reduceMotion preference. When enabled, snap() is used so there
    // is no animated transition — the scale changes instantaneously, which is the correct
    // accessible behaviour for users who experience motion sickness or vestibular disorders.
    val reduceMotion = settings.reduceMotion
    val iconScale by animateFloatAsState(
        targetValue = if (isAnimating) 1.1f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(300),
        label = "iconScale"
    )

    fun showBiometricPrompt() {
        val helper = biometricHelper ?: return
        isAnimating = true

        // TODO(D1): Pass a CryptoObject built from BiometricCryptoManager so the biometric
        // result is cryptographically bound to a Keystore key (defends against callback
        // spoofing on rooted devices). Requires enrollment-flow wiring in SettingsScreen
        // to seal a proof token at PIN-setup time.
        helper.prompt(
            title = context.getString(R.string.biometric_title),
            subtitle = context.getString(R.string.biometric_subtitle),
            negativeText = if (settings.pinEnabled) {
                context.getString(R.string.biometric_negative)
            } else {
                context.getString(R.string.cancel)
            },
            onSuccess = { _ ->
                isAnimating = false
                onAuthSuccess()
            },
            onFallbackToPin = {
                isAnimating = false
                if (settings.pinEnabled) onUsePinClick()
            },
            onPermanentLockout = {
                isAnimating = false
                errorMessage = context.getString(R.string.biometric_too_many_attempts)
                if (settings.pinEnabled) onUsePinClick()
            },
            onError = { message ->
                isAnimating = false
                errorMessage = message
            },
            onFailed = {
                isAnimating = false
                failureCount++
                if (failureCount >= maxFailures && settings.pinEnabled) {
                    errorMessage = context.getString(R.string.biometric_too_many_attempts)
                    onUsePinClick()
                } else {
                    errorMessage = context.getString(R.string.biometric_failed)
                }
            },
        )
    }

    // L4: Cancel any in-progress prompt when this composition is disposed. This prevents
    // stale callbacks from the previous BiometricPrompt (tied to the old FragmentActivity)
    // landing on the new composition's lambda closures after a rotation or back-navigation.
    // Even with android:configChanges preventing recreation, this guards against any other
    // path that disposes the composable while a prompt is showing (e.g. back-press).
    DisposableEffect(activity) {
        onDispose { biometricHelper?.cancelPendingAuthentication() }
    }

    // L3: Keyed on `activity` instead of `Unit` so the effect is tied to the specific
    // FragmentActivity instance. With android:configChanges declared, `activity` does not
    // change on rotation, meaning this effect fires exactly once per activity instance
    // (preventing double-prompt). If the activity is ever recreated, the new instance gets
    // a fresh effect trigger — which is the correct behaviour.
    LaunchedEffect(activity) {
        if (settings.biometricEnabled && canUseBiometric) {
            showBiometricPrompt()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Fingerprint icon with animated background
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .scale(iconScale)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                            )
                        )
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.biometric_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.biometric_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            // Error message with better visibility
            errorMessage?.let {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            if (settings.biometricEnabled && canUseBiometric) {
                Button(
                    onClick = {
                        errorMessage = null
                        showBiometricPrompt()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.biometric_unlock),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (settings.pinEnabled) {
                OutlinedButton(
                    onClick = onUsePinClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.biometric_negative),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
