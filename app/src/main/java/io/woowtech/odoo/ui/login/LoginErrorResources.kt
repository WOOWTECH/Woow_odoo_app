package io.woowtech.odoo.ui.login

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import io.woowtech.odoo.R
import io.woowtech.odoo.domain.model.AuthResult

/** Store validation meaning, not localized text, so a locale change can re-render errors. */
enum class LoginFieldError {
    SERVER_URL_REQUIRED, DATABASE_REQUIRED, USERNAME_REQUIRED, PASSWORD_REQUIRED,
    HTTPS_REQUIRED, INVALID_URL
}

@StringRes
internal fun LoginFieldError.messageResource(): Int = when (this) {
    LoginFieldError.SERVER_URL_REQUIRED -> R.string.login_server_url_required
    LoginFieldError.DATABASE_REQUIRED -> R.string.login_database_required
    LoginFieldError.USERNAME_REQUIRED -> R.string.login_username_required
    LoginFieldError.PASSWORD_REQUIRED -> R.string.login_password_required
    LoginFieldError.HTTPS_REQUIRED -> R.string.error_https
    LoginFieldError.INVALID_URL -> R.string.error_invalid_url
}

/** UNKNOWN retains the existing server-message fallback rather than inventing a new contract. */
@StringRes
internal fun AuthResult.ErrorType.messageResource(): Int? = when (this) {
    AuthResult.ErrorType.NETWORK_ERROR -> R.string.error_network
    AuthResult.ErrorType.INVALID_URL -> R.string.error_invalid_url
    AuthResult.ErrorType.DATABASE_NOT_FOUND -> R.string.error_database
    AuthResult.ErrorType.INVALID_CREDENTIALS -> R.string.error_auth
    AuthResult.ErrorType.SESSION_EXPIRED -> R.string.error_session
    AuthResult.ErrorType.HTTPS_REQUIRED -> R.string.error_https
    AuthResult.ErrorType.SERVER_ERROR -> R.string.error_server
    AuthResult.ErrorType.UNKNOWN -> null
}

/**
 * Error-card text. A non-200 sign-in response names its HTTP status exactly once, inside the
 * localized phrase (`error_server_http`, iOS `error_server_http_%lld` parity); every other error
 * keeps its [messageResource] text, and UNKNOWN keeps the original [fallback] message.
 */
@Composable
@ReadOnlyComposable
internal fun LoginUiState.errorMessage(fallback: String): String {
    val status = httpStatus
    if (errorType == AuthResult.ErrorType.SERVER_ERROR && status != null) {
        return stringResource(R.string.error_server_http, status)
    }
    return errorType?.messageResource()?.let { stringResource(it) } ?: fallback
}
