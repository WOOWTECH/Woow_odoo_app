package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val encryptedPrefs: EncryptedPrefs,
    private val odooClient: OdooJsonRpcClient
) {
    val allAccounts: Flow<List<OdooAccount>> = accountDao.getAllAccounts()
    val activeAccount: Flow<OdooAccount?> = accountDao.getActiveAccount()

    suspend fun getActiveAccountOnce(): OdooAccount? = accountDao.getActiveAccountOnce()

    suspend fun authenticate(
        serverUrl: String,
        database: String,
        username: String,
        password: String
    ): AuthResult {
        val fullUrl = if (serverUrl.startsWith("https://")) serverUrl else "https://$serverUrl"

        val result = odooClient.authenticate(fullUrl, database, username, password)

        if (result is AuthResult.Success) {
            // Check if account already exists
            val existingAccount = accountDao.findAccount(fullUrl, database, username)

            val account = existingAccount?.copy(
                displayName = result.displayName,
                userId = result.userId,
                lastLogin = System.currentTimeMillis(),
                isActive = true
            ) ?: OdooAccount(
                serverUrl = fullUrl,
                database = database,
                username = username,
                displayName = result.displayName,
                userId = result.userId,
                isActive = true
            )

            // Deactivate other accounts and save this one
            accountDao.deactivateAllAccounts()
            accountDao.insertAccount(account)

            // Save password securely
            encryptedPrefs.savePassword(account.id, password)
        }

        return result
    }

    suspend fun switchAccount(accountId: String): Boolean {
        val account = accountDao.getAccountById(accountId) ?: return false
        val password = encryptedPrefs.getPassword(accountId) ?: return false

        // Try to re-authenticate
        val result = odooClient.authenticate(
            account.fullServerUrl,
            account.database,
            account.username,
            password
        )

        return if (result is AuthResult.Success) {
            accountDao.deactivateAllAccounts()
            accountDao.activateAccount(accountId)
            accountDao.updateLastLogin(accountId)
            true
        } else {
            false
        }
    }

    suspend fun logout(accountId: String? = null) {
        val id = accountId ?: accountDao.getActiveAccountOnce()?.id ?: return
        val account = accountDao.getAccountById(id) ?: return

        // Clear cookies
        val host = account.fullServerUrl.removePrefix("https://").split("/").first()
        odooClient.clearCookies(host)

        // Remove password
        encryptedPrefs.removePassword(id)

        // Delete account from database
        accountDao.deleteAccountById(id)
    }

    suspend fun removeAccount(accountId: String) {
        encryptedPrefs.removePassword(accountId)
        accountDao.deleteAccountById(accountId)
    }

    fun getSessionId(serverUrl: String): String? {
        val host = serverUrl.removePrefix("https://").removePrefix("http://").split("/").first()
        return odooClient.getSessionId(host)
    }

    fun getSessionCookies(serverUrl: String): List<okhttp3.Cookie> {
        val host = serverUrl.removePrefix("https://").removePrefix("http://").split("/").first()
        return odooClient.getSessionCookies(host)
    }

    suspend fun getAccountCount(): Int = accountDao.getAccountCount()
}
