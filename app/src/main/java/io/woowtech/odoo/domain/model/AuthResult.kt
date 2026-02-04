package io.woowtech.odoo.domain.model

sealed class AuthResult {
    data class Success(
        val userId: Int,
        val sessionId: String,
        val username: String,
        val displayName: String
    ) : AuthResult()

    data class Error(val message: String, val type: ErrorType) : AuthResult()

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
