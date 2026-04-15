package io.woowtech.odoo.ui

import android.os.Bundle
import android.view.WindowManager
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
 * which screen was visible.
 *
 * FLAG_SECURE is set for the entire window in [onCreate] so the auth screens are never
 * exposed in the Recents thumbnail, even during the brief gap that would arise if the flag
 * were set per-screen via a [androidx.compose.runtime.DisposableEffect]. The flag persists
 * for the lifetime of the window and is not cleared on navigate-away from auth screens,
 * which is intentional — the app never shows content that should appear in screenshots.
 *
 * The [ProcessLifecycleOwner] observer reference is stored so it can be explicitly removed
 * in [onDestroy], preventing observer leaks when [android:configChanges] is NOT declared
 * in the manifest. With configChanges declared the Activity is not recreated on rotation
 * or locale/font-scale changes, so this removal is an extra defensive layer. (L1 fix)
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    private lateinit var processLifecycleObserver: LifecycleEventObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // L6: Set FLAG_SECURE at the window level once, covering the entire app lifetime.
        // This eliminates the rotation gap where per-screen DisposableEffects would briefly
        // drop the flag between teardown and re-composition on a config change.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // L1: Store the observer reference so it can be removed in onDestroy.
        // ProcessLifecycleOwner lives for the entire process; without explicit removal,
        // each Activity recreation (e.g. on locale change) would register an additional
        // observer, accumulating leaked callbacks for the process lifetime.
        processLifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                authViewModel.onAppBackgrounded()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

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

    override fun onDestroy() {
        super.onDestroy()
        // L1: Remove the ProcessLifecycleOwner observer to prevent leaks.
        // Even though android:configChanges prevents recreation on rotation/locale/font-scale,
        // this guard covers any remaining path that destroys the Activity (e.g. system
        // memory pressure, explicit finish, or future manifest changes).
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
    }
}
