package io.woowtech.odoo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.api.SessionReauthenticator
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.location.LocationPermissionGate
import io.woowtech.odoo.data.push.DeepLinkManager
import io.woowtech.odoo.data.push.PendingDeepLink
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.ReloginRequest
import io.woowtech.odoo.data.repository.ReloginSignal
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WebViewCredentials(
    val serverUrl: String,
    val database: String,
    val username: String,
    val password: String
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val encryptedPrefs: EncryptedPrefs,
    private val deepLinkManager: DeepLinkManager,
    private val reloginSignal: ReloginSignal,
    val locationPermissionGate: LocationPermissionGate,
    private val sessionReauthenticator: SessionReauthenticator,
) : ViewModel() {

    val activeAccount: Flow<OdooAccount?> = accountRepository.activeAccount

    /**
     * Outstanding request to re-authenticate an account after a background session re-auth failed
     * unrecoverably (stored password rejected, or the per-account circuit breaker tripped). Null when
     * nothing is pending. The UI observes this to prompt the user; it must call [clearReloginRequest]
     * once handled. No credential is ever carried in this signal.
     */
    val reloginRequest: StateFlow<ReloginRequest?> = reloginSignal.pending

    /**
     * The pending notification deep link, bound to the account that must display it. The UI passes
     * its `url` to the WebView only when [PendingDeepLink.accountId] matches the active account, and
     * calls [consumePendingDeepLink] once the load has finished — never on mere state emission.
     */
    val pendingDeepLink: StateFlow<PendingDeepLink?> = deepLinkManager.pending

    private val _credentials = MutableStateFlow<WebViewCredentials?>(null)
    val credentials: StateFlow<WebViewCredentials?> = _credentials.asStateFlow()

    init {
        loadCredentials()
    }

    private fun loadCredentials() {
        viewModelScope.launch {
            val account = accountRepository.getActiveAccountOnce()
            if (account != null) {
                val password = encryptedPrefs.getPassword(account.id)
                if (password != null) {
                    _credentials.value = WebViewCredentials(
                        serverUrl = account.fullServerUrl,
                        database = account.database,
                        username = account.username,
                        password = password
                    )
                }
            }
        }
    }

    fun refreshCredentials() {
        loadCredentials()
    }

    /**
     * Returns whether the POST_NOTIFICATIONS runtime dialog has already been auto-launched once for
     * this install. Used by the main screen to guarantee the OS dialog auto-launches at most once.
     */
    fun wasNotificationPermissionRequested(): Boolean {
        return encryptedPrefs.wasPostNotificationPermissionRequested()
    }

    /**
     * Records that the app has auto-launched the POST_NOTIFICATIONS runtime dialog, so it is never
     * triggered automatically again for this install.
     */
    fun markNotificationPermissionRequested() {
        encryptedPrefs.setPostNotificationPermissionRequested()
    }

    /** Clears the outstanding re-login request once the UI has surfaced it (or the user re-logged in). */
    fun clearReloginRequest() {
        reloginSignal.clear()
    }

    fun getSessionId(serverUrl: String): String? {
        return accountRepository.getSessionId(serverUrl)
    }

    /**
     * Attempts to silently re-authenticate the active account's expired WebView session for [host],
     * mirroring the iOS `attemptSelfHealOrLogin` recovery. Delegates to the shared
     * [SessionReauthenticator] — the very same engine the FCM/REST path uses — so both recovery
     * routes share one single-flight lock, one-retry cap, bad-credential STOP, and circuit breaker
     * and can never race or resend a known-bad password.
     *
     * Returns true when a fresh session cookie was established on the shared cookie jar; the caller
     * must then read the refreshed session id via [getSessionId] and re-inject it into the WebView's
     * `CookieManager`. Returns false when recovery is impossible (no stored password, bad
     * credentials, non-https/unknown host, or an open circuit), in which case the caller must
     * surface an explicit re-login rather than retry.
     *
     * Runs the (blocking) re-auth on [Dispatchers.IO]; safe to call from the main thread.
     */
    suspend fun selfHealActiveAccount(host: String): Boolean {
        return withContext(Dispatchers.IO) {
            sessionReauthenticator.reauthenticateForHost(host)
        }
    }

    /**
     * Consumes and returns the pending deep link URL for [accountId], or null if there is none for
     * that account (or it has expired). Single-consume: a link is returned at most once. Call this
     * only once the WebView has finished loading a page on the account's own host.
     */
    fun consumePendingDeepLink(accountId: String): String? {
        return deepLinkManager.consumeFor(accountId)
    }

    /**
     * Drops any pending deep link that does not belong to [activeAccountId]. Called when the active
     * account changes so a link queued for another account can never leak into this one.
     */
    fun dropForeignPendingDeepLink(activeAccountId: String) {
        deepLinkManager.dropIfNotTarget(activeAccountId)
    }
}
