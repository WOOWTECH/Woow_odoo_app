package io.woowtech.odoo.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val hasActiveAccount: StateFlow<Boolean?> = accountRepository.activeAccount
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // v1.0.21: Biometric auth temporarily disabled - always skip auth screen
    // Keep the field but always return false
    val requiresAuth: StateFlow<Boolean> = MutableStateFlow(false)

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    val settings = settingsRepository.settings

    fun setAuthenticated(authenticated: Boolean) {
        _isAuthenticated.value = authenticated
    }

    fun verifyPin(pin: String): Boolean {
        return settingsRepository.verifyPin(pin)
    }

    fun getRemainingAttempts(): Int = settingsRepository.getRemainingAttempts()

    fun isLockedOut(): Boolean = settingsRepository.isLockedOut()

    fun getLockoutRemainingMs(): Long = settingsRepository.getLockoutRemainingMs()
}
