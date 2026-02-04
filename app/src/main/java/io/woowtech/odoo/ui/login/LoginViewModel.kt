package io.woowtech.odoo.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val step: LoginStep = LoginStep.SERVER_INFO,
    val serverUrl: String = "",
    val database: String = "",
    val username: String = "",
    val password: String = "",
    val rememberMe: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val serverUrlError: String? = null,
    val databaseError: String? = null,
    val usernameError: String? = null,
    val passwordError: String? = null
)

enum class LoginStep {
    SERVER_INFO,
    CREDENTIALS
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            serverUrlError = null,
            error = null
        )
    }

    fun updateDatabase(database: String) {
        _uiState.value = _uiState.value.copy(
            database = database,
            databaseError = null,
            error = null
        )
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(
            username = username,
            usernameError = null,
            error = null
        )
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(
            password = password,
            passwordError = null,
            error = null
        )
    }

    fun updateRememberMe(remember: Boolean) {
        _uiState.value = _uiState.value.copy(rememberMe = remember)
    }

    fun goToNextStep() {
        val state = _uiState.value

        // Validate server URL
        if (state.serverUrl.isBlank()) {
            _uiState.value = state.copy(serverUrlError = "Server URL is required")
            return
        }

        // Validate database
        if (state.database.isBlank()) {
            _uiState.value = state.copy(databaseError = "Database name is required")
            return
        }

        // Check URL format (must not start with http://)
        if (state.serverUrl.startsWith("http://")) {
            _uiState.value = state.copy(serverUrlError = "HTTPS is required for security")
            return
        }

        _uiState.value = state.copy(step = LoginStep.CREDENTIALS, error = null)
    }

    fun goBack() {
        _uiState.value = _uiState.value.copy(
            step = LoginStep.SERVER_INFO,
            error = null,
            passwordError = null,
            usernameError = null
        )
    }

    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value

        // Validate credentials
        if (state.username.isBlank()) {
            _uiState.value = state.copy(usernameError = "Username is required")
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = state.copy(passwordError = "Password is required")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            val serverUrl = if (state.serverUrl.startsWith("https://")) {
                state.serverUrl
            } else {
                "https://${state.serverUrl}"
            }

            val result = accountRepository.authenticate(
                serverUrl = serverUrl,
                database = state.database,
                username = state.username,
                password = state.password
            )

            when (result) {
                is AuthResult.Success -> {
                    _uiState.value = state.copy(isLoading = false)
                    onSuccess()
                }
                is AuthResult.Error -> {
                    val errorMessage = when (result.type) {
                        AuthResult.ErrorType.NETWORK_ERROR -> "Unable to connect to server"
                        AuthResult.ErrorType.INVALID_URL -> "Invalid server URL"
                        AuthResult.ErrorType.DATABASE_NOT_FOUND -> "Database not found"
                        AuthResult.ErrorType.INVALID_CREDENTIALS -> "Invalid username or password"
                        AuthResult.ErrorType.SESSION_EXPIRED -> "Session expired"
                        AuthResult.ErrorType.HTTPS_REQUIRED -> "Secure connection required (HTTPS)"
                        AuthResult.ErrorType.SERVER_ERROR -> "Server error"
                        AuthResult.ErrorType.UNKNOWN -> result.message
                    }
                    _uiState.value = state.copy(
                        isLoading = false,
                        error = errorMessage
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
