package io.woowtech.odoo.ui.login

import io.woowtech.odoo.R
import io.woowtech.odoo.domain.model.AuthResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LoginErrorResourcesTest {
    @Test
    fun `Given every field validation error when rendered then uses its localized resource`() {
        val expected = mapOf(
            LoginFieldError.SERVER_URL_REQUIRED to R.string.login_server_url_required,
            LoginFieldError.DATABASE_REQUIRED to R.string.login_database_required,
            LoginFieldError.USERNAME_REQUIRED to R.string.login_username_required,
            LoginFieldError.PASSWORD_REQUIRED to R.string.login_password_required,
            LoginFieldError.HTTPS_REQUIRED to R.string.error_https,
            LoginFieldError.INVALID_URL to R.string.error_invalid_url
        )
        assertEquals(expected, LoginFieldError.entries.associateWith { it.messageResource() })
    }

    @Test
    fun `Given known auth errors when rendered then both brands use localized resources`() {
        val expected = mapOf(
            AuthResult.ErrorType.NETWORK_ERROR to R.string.error_network,
            AuthResult.ErrorType.INVALID_URL to R.string.error_invalid_url,
            AuthResult.ErrorType.DATABASE_NOT_FOUND to R.string.error_database,
            AuthResult.ErrorType.INVALID_CREDENTIALS to R.string.error_auth,
            AuthResult.ErrorType.SESSION_EXPIRED to R.string.error_session,
            AuthResult.ErrorType.HTTPS_REQUIRED to R.string.error_https,
            AuthResult.ErrorType.SERVER_ERROR to R.string.error_server,
            AuthResult.ErrorType.UNKNOWN to null
        )
        assertEquals(expected, AuthResult.ErrorType.entries.associateWith { it.messageResource() })
    }

    @Test
    fun `Given unknown auth error when rendered then resource is absent to preserve original message`() {
        assertNull(AuthResult.ErrorType.UNKNOWN.messageResource())
    }
}
