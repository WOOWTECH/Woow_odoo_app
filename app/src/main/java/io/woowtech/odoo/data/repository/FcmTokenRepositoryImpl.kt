package io.woowtech.odoo.data.repository

import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers and unregisters FCM tokens with Odoo servers via HTTP POST
 * to /woow_fcm_push/register endpoint. Stores token locally in EncryptedPrefs.
 */
@Singleton
class FcmTokenRepositoryImpl @Inject constructor(
    private val apiClient: OdooJsonRpcClient,
    private val encryptedPrefs: EncryptedPrefs,
    private val accountDao: AccountDao
) : FcmTokenRepository {

    override suspend fun registerTokenForAllAccounts(token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            encryptedPrefs.saveFcmToken(token)
            val accounts = accountDao.getAllAccountsList()
            var lastError: Throwable? = null

            accounts.forEach { account ->
                registerToken(accountId = account.id, token = token)
                    .onFailure { lastError = it }
            }

            if (lastError != null) {
                Timber.e(lastError, "Failed to register token with some accounts")
                Result.failure(lastError!!)
            } else {
                Timber.d("FCM token registered with %d accounts", accounts.size)
                Result.success(Unit)
            }
        }

    override suspend fun registerToken(accountId: String, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // TODO: POST to /woow_fcm_push/register when Odoo module is ready
                Timber.d("Would register token for account %s (Odoo module not yet deployed)", accountId)
            }
        }

    override suspend fun unregisterToken(accountId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // TODO: POST to /woow_fcm_push/unregister when Odoo module is ready
                Timber.d("Would unregister token for account %s", accountId)
            }
        }

    override fun getStoredToken(): String? {
        return encryptedPrefs.getFcmToken()
    }
}
