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

    fun logout() {
        viewModelScope.launch {
            accountRepository.logout()
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            accountRepository.removeAccount(accountId)
        }
    }
}
