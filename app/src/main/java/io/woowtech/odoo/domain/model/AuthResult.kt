package io.woowtech.odoo.domain.model

sealed class AuthResult {
    data class Success(
        val userId: Int,
        val sessionId: String,
        val username: String,
        val displayName: String
    ) : AuthResult()

    /**
     * @param httpStatus the non-200 HTTP status the server answered sign-in with (e.g. Cloudflare 530),
     * shown in the localized `error_server_http` text; null when the failure carries no status.
     */
    data class Error(val message: String, val type: ErrorType, val httpStatus: Int? = null) : AuthResult()

    enum class ErrorType {
        NETWORK_ERROR,
        INVALID_URL,
        DATABASE_NOT_FOUND,
        INVALID_CREDENTIALS,
        SESSION_EXPIRED,
        HTTPS_REQUIRED,
        SERVER_ERROR,
        UNKNOWN
    }
}
