package io.woowtech.odoo.ui

import android.content.Intent
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
import io.woowtech.odoo.data.push.DeepLinkManager
import io.woowtech.odoo.data.push.DeepLinkRoute
import io.woowtech.odoo.data.push.DeepLinkRouter
import io.woowtech.odoo.data.push.DeepLinkValidator
import io.woowtech.odoo.data.push.NotificationHelper
import io.woowtech.odoo.data.push.RoutableAccount
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.ui.auth.AuthViewModel
import io.woowtech.odoo.ui.navigation.WoowOdooNavHost
import io.woowtech.odoo.ui.theme.WoowTechOdooTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Main hosting activity. Extends [FragmentActivity] (not [androidx.activity.ComponentActivity])
 * because [androidx.biometric.BiometricPrompt] requires a FragmentActivity host. All Compose
 * APIs used here (`setContent`, `enableEdgeToEdge`) remain available via inheritance.
 *
 * Observes [ProcessLifecycleOwner] for [Lifecycle.Event.ON_STOP] to call
 * [AuthViewModel.onAppBackgrounded] when the entire app process moves to the background.
 * This ensures re-authentication is required after the app is backgrounded regardless of
 * which screen was visible. The observer reference is stored so it can be explicitly removed
 * in [onDestroy], preventing observer leaks. (L1 fix)
 *
 * FLAG_SECURE is set for the entire window in [onCreate] so the auth screens are never
 * exposed in the Recents thumbnail, even during the brief gap that would arise if the flag
 * were set per-screen via a [androidx.compose.runtime.DisposableEffect]. (L6/M2 fix)
 *
 * Deep-link host validation (C2): reads the active account's server host from
 * [AccountRepository] before passing it to [DeepLinkValidator], so external-host URLs are
 * rejected. If there is no active account, all deep links are rejected.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var deepLinkManager: DeepLinkManager
    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val authViewModel: AuthViewModel by viewModels()

    private lateinit var processLifecycleObserver: LifecycleEventObserver

    // Scope for deep-link host resolution — tied to activity lifetime.
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // L6/M2: Set FLAG_SECURE at the window level once, covering the entire app lifetime.
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

        // Test hooks must run BEFORE setContent so seeded state is visible
        // to the first composition (auth gate reads it immediately).
        // lifecycleScope is passed so setPin (now suspend) is never on GlobalScope.
        TestHooks.applyIfPresent(intent, settingsRepository, lifecycleScope)

        handleDeepLinkIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Warm-start re-seeding for E2E tests (no-op in release).
        TestHooks.applyIfPresent(intent, settingsRepository, lifecycleScope)
        handleDeepLinkIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // L1: Remove the ProcessLifecycleOwner observer to prevent leaks.
        // Even though android:configChanges prevents recreation on rotation/locale/font-scale,
        // this guard covers any remaining path that destroys the Activity (e.g. system
        // memory pressure, explicit finish, or future manifest changes).
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        activityScope.cancel()
    }

    /**
     * Extracts a notification-tap deep link, resolves which account it belongs to, switches to
     * that account if needed, and queues the link bound to that account for the WebView to apply.
     *
     * Multi-account routing (P0 cross-tenant isolation):
     * - The FCM payload carries an opaque `odoo_tenant_id`. [DeepLinkRouter] matches it against the
     *   locally persisted tenant ids to find the owning account.
     * - **Present-but-unresolved tenant id, or a target account that is not logged in, is dropped**
     *   — the link is never applied to the currently active account.
     * - **Missing tenant id (old plugin)** falls back to the previous behaviour: validate the URL
     *   against the active account's host and queue it for that account.
     *
     * Resolution is async (Room query) so it happens off the main thread. The intent is read
     * synchronously before the coroutine launches so it cannot be missed if the Activity finishes
     * before the coroutine completes.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val actionUrl = intent?.getStringExtra(NotificationHelper.EXTRA_ACTION_URL) ?: return
        val tenantId = intent.getStringExtra(NotificationHelper.EXTRA_TENANT_ID)

        activityScope.launch(Dispatchers.IO) {
            val accounts = accountRepository.getAllAccountsOnce()
            val routableAccounts = accounts.map { account ->
                RoutableAccount(
                    id = account.id,
                    tenantId = account.tenantId,
                    serverHost = account.serverHost(),
                )
            }

            when (
                val route = DeepLinkRouter.route(
                    tenantId = tenantId,
                    actionUrl = actionUrl,
                    accounts = routableAccounts,
                    isLoggedIn = { accountRepository.isLoggedIn(it) },
                )
            ) {
                is DeepLinkRoute.SwitchAndApply -> applyResolvedDeepLink(route.accountId, route.url)
                is DeepLinkRoute.ApplyToActive -> applyOldPayloadDeepLink(route.url)
                is DeepLinkRoute.Drop ->
                    // No user data is logged — only the reason category.
                    Timber.w("Dropping notification deep link: %s", route.reason)
            }
        }
    }

    /**
     * Switches to the [accountId] that owns the notification (if it is not already active) and then
     * queues [url] bound to that account. The pending link is set only after the switch succeeds so
     * a failed switch cannot leave a link primed against the wrong account.
     */
    private suspend fun applyResolvedDeepLink(accountId: String, url: String) {
        val activeId = accountRepository.getActiveAccountOnce()?.id
        if (activeId != accountId) {
            val switched = accountRepository.switchAccount(accountId)
            if (!switched) {
                Timber.w("Dropping notification deep link: switch to target account failed")
                return
            }
        }
        deepLinkManager.setPending(url = url, accountId = accountId)
        Timber.d("Deep link pending for resolved account")
    }

    /**
     * Old-plugin fallback: the payload carried no tenant id. Validate [url] against the active
     * account's host (as before) and, if valid, queue it bound to the active account.
     */
    private suspend fun applyOldPayloadDeepLink(url: String) {
        val active = accountRepository.activeAccount.firstOrNull()
        if (active == null) {
            Timber.w("Rejecting deep link — no active account to validate against")
            return
        }

        if (DeepLinkValidator.isValid(url = url, serverHost = active.serverHost())) {
            deepLinkManager.setPending(url = url, accountId = active.id)
            Timber.d("Deep link pending for active account (old-payload path)")
        } else {
            Timber.w("Rejected invalid deep link")
        }
    }

    /** Bare host of this account's server, with scheme and path stripped. */
    private fun io.woowtech.odoo.domain.model.OdooAccount.serverHost(): String =
        serverUrl.removePrefix("https://").removePrefix("http://").split("/").first()
}
