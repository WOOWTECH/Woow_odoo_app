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
    val errorType: AuthResult.ErrorType? = null,
    val serverUrlError: LoginFieldError? = null,
    val databaseError: LoginFieldError? = null,
    val usernameError: LoginFieldError? = null,
    val passwordError: LoginFieldError? = null
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
            error = null, errorType = null
        )
    }

    fun updateDatabase(database: String) {
        _uiState.value = _uiState.value.copy(
            database = database,
            databaseError = null,
            error = null, errorType = null
        )
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(
            username = username,
            usernameError = null,
            error = null, errorType = null
        )
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(
            password = password,
            passwordError = null,
            error = null, errorType = null
        )
    }

    fun updateRememberMe(remember: Boolean) {
        _uiState.value = _uiState.value.copy(rememberMe = remember)
    }

    fun goToNextStep() {
        val state = _uiState.value

        // Validate server URL
        if (state.serverUrl.isBlank()) {
            _uiState.value = state.copy(serverUrlError = LoginFieldError.SERVER_URL_REQUIRED)
            return
        }

        // Validate database
        if (state.database.isBlank()) {
            _uiState.value = state.copy(databaseError = LoginFieldError.DATABASE_REQUIRED)
            return
        }

        // Check URL format (must not start with http://)
        if (ServerUrlInput.isInsecure(state.serverUrl)) {
            _uiState.value = state.copy(serverUrlError = LoginFieldError.HTTPS_REQUIRED)
            return
        }

        val normalized = ServerUrlInput.normalize(state.serverUrl)
        if (normalized == null) {
            _uiState.value = state.copy(serverUrlError = LoginFieldError.INVALID_URL)
            return
        }

        _uiState.value = state.copy(
            step = LoginStep.CREDENTIALS,
            serverUrl = ServerUrlInput.displayValue(normalized),
            database = state.database.trim(),
            error = null, errorType = null
        )
    }

    fun goBack() {
        _uiState.value = _uiState.value.copy(
            step = LoginStep.SERVER_INFO,
            error = null, errorType = null,
            passwordError = null,
            usernameError = null
        )
    }

    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value

        // Validate credentials
        if (state.username.isBlank()) {
            _uiState.value = state.copy(usernameError = LoginFieldError.USERNAME_REQUIRED)
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = state.copy(passwordError = LoginFieldError.PASSWORD_REQUIRED)
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null, errorType = null)

            val serverUrl = ServerUrlInput.normalize(state.serverUrl)
            if (serverUrl == null) {
                _uiState.value = state.copy(isLoading = false, error = "Invalid server URL", errorType = AuthResult.ErrorType.INVALID_URL)
                return@launch
            }

            // 密碼不修剪：空白可能是密碼的一部分
            val result = accountRepository.authenticate(
                serverUrl = serverUrl,
                database = state.database.trim(),
                username = state.username.trim(),
                password = state.password,
                rememberPassword = state.rememberMe,
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
                        error = errorMessage,
                        errorType = result.type
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, errorType = null)
    }
}
