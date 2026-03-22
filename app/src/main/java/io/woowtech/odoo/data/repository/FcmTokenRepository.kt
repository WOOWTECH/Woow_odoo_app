package io.woowtech.odoo.data.repository

/**
 * Manages FCM token registration with Odoo servers.
 * Token is stored locally in EncryptedPrefs and registered
 * with all active Odoo accounts via HTTP controller.
 */
interface FcmTokenRepository {

    /**
     * Registers the FCM token with all active Odoo server accounts.
     * Called when Firebase issues a new token via onNewToken.
     */
    suspend fun registerTokenForAllAccounts(token: String): Result<Unit>

    /**
     * Registers the FCM token with a specific Odoo account.
     */
    suspend fun registerToken(accountId: String, token: String): Result<Unit>

    /**
     * Unregisters the FCM token from a specific Odoo account.
     * Called when user logs out of an account.
     */
    suspend fun unregisterToken(accountId: String): Result<Unit>

    /**
     * Returns the locally stored FCM token, or null if not yet obtained.
     */
    fun getStoredToken(): String?
}
