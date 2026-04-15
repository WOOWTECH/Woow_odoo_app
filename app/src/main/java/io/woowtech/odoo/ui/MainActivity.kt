package io.woowtech.odoo.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import io.woowtech.odoo.ui.auth.AuthViewModel
import io.woowtech.odoo.ui.navigation.WoowOdooNavHost
import io.woowtech.odoo.ui.theme.WoowTechOdooTheme

/**
 * Main hosting activity. Extends [FragmentActivity] (not [androidx.activity.ComponentActivity])
 * because [androidx.biometric.BiometricPrompt] requires a FragmentActivity host. All Compose
 * APIs used here (`setContent`, `enableEdgeToEdge`) remain available via inheritance.
 *
 * Observes [ProcessLifecycleOwner] for [Lifecycle.Event.ON_STOP] to call
 * [AuthViewModel.onAppBackgrounded] when the entire app process moves to the background.
 * This ensures re-authentication is required after the app is backgrounded regardless of
 * which screen was visible. (H3)
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    authViewModel.onAppBackgrounded()
                }
            }
        )

        setContent {
            WoowTechOdooTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WoowOdooNavHost()
                }
            }
        }
    }
}
