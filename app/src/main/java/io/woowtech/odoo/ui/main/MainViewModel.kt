package io.woowtech.odoo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.local.EncryptedPrefs
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
    private val encryptedPrefs: EncryptedPrefs
) : ViewModel() {

    val activeAccount: Flow<OdooAccount?> = accountRepository.activeAccount

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
}
