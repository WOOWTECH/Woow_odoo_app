package io.woowtech.odoo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.location.LocationPermissionGate
import io.woowtech.odoo.data.push.DeepLinkManager
import io.woowtech.odoo.data.push.PendingDeepLink
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val locationPermissionGate: LocationPermissionGate,
) : ViewModel() {

    val activeAccount: Flow<OdooAccount?> = accountRepository.activeAccount

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

    fun getSessionId(serverUrl: String): String? {
        return accountRepository.getSessionId(serverUrl)
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
