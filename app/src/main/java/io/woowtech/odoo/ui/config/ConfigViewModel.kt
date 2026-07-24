package io.woowtech.odoo.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val accountRepository: AccountRepository
) : ViewModel() {

    val activeAccount: Flow<OdooAccount?> = accountRepository.activeAccount
    val allAccounts: Flow<List<OdooAccount>> = accountRepository.allAccounts

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            accountRepository.switchAccount(accountId)
        }
    }

    /**
     * Logs out the CURRENT (active) account, then invokes [onComplete] with whether the app should
     * STAY authenticated: `true` when another account was promoted (multi-account fallback) and the
     * caller should return to the main screen, `false` when no accounts remain and the caller should
     * navigate to login. [onComplete] runs AFTER logout finishes (on the main dispatcher), which
     * fixes the previous async race where navigation fired before logout completed.
     */
    fun logout(onComplete: (stayAuthenticated: Boolean) -> Unit) {
        viewModelScope.launch {
            val stayAuthenticated = accountRepository.logout()
            onComplete(stayAuthenticated)
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            accountRepository.removeAccount(accountId)
        }
    }
}
