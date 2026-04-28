package io.woowtech.odoo.data.repository

import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers and unregisters FCM tokens with Odoo servers via HTTP POST
 * to `/woow_fcm_push/register` and `/woow_fcm_push/unregister` endpoints.
 * Stores token locally in EncryptedPrefs.
 *
 * Registration uses the current Odoo session cookie for authentication — the Odoo
 * module requires `auth='user'` so the cookie must be present. If the session has
 * expired (HTTP 401 / Odoo `{"error": ...}` response) the failure is logged but
 * not propagated — the token will be re-registered on the next successful auth.
 *
 * Transient errors (5xx, network timeout) are returned as [Result.failure] so the
 * caller can apply retry logic if needed.
 */
@Singleton
class FcmTokenRepositoryImpl @Inject constructor(
    private val encryptedPrefs: EncryptedPrefs,
    private val accountDao: AccountDao,
    private val sessionCookieProvider: SessionCookieProvider,
) : FcmTokenRepository {

    private val gson = Gson()

    /**
     * Serializes register / unregister calls so that concurrent callers
     * (`WoowFcmService.onNewToken` and `AccountRepository.authenticate`)
     * cannot race to write to `woow.fcm.device`.
     *
     * Without this, two POSTs in flight on `Dispatchers.IO` (a thread pool)
     * can resolve out-of-order and the older token can become the
     * server-side active record. The mutex turns the
     * `saveFcmToken + accountDao read + per-account register POST` sequence
     * into a single critical section.
     *
     * Granularity: the entire FcmTokenRepositoryImpl serializes its outbound
     * registrations. This is acceptable: the operations are infrequent
     * (login, switch, account removal, FCM rotation) and individually
     * complete in a few hundred ms.
     */
    private val registrationMutex = Mutex()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                sessionCookieProvider.getCookiesForHost(url.host)
        })
        .build()

    override suspend fun registerTokenForAllAccounts(token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            registrationMutex.withLock {
                encryptedPrefs.saveFcmToken(token)
                val accounts = accountDao.getAllAccountsList()

                // Empty-collection paranoia (CLAUDE.md "Repository-Event Symmetry").
                // If called before any account exists (fresh install — onNewToken
                // fires before login), the token is saved locally but POSTs nothing.
                // Log this loudly so it's correlatable with missed notifications;
                // AccountRepository.authenticate() / switchAccount() replay the
                // saved token via registerSavedFcmToken once an account is present.
                if (accounts.isEmpty()) {
                    Timber.w(
                        "FCM token saved locally but no accounts to register with — " +
                            "AccountRepository will replay on next login/switch",
                    )
                    return@withLock Result.success(Unit)
                }

                // Aggregate failures across all per-account POSTs so the
                // caller can see the FULL picture, not just whichever
                // failed last (which was the prior behaviour and would
                // hide multi-account fan-out problems). Each individual
                // failure is logged separately for forensic debugging.
                val failures = mutableListOf<Pair<String, Throwable>>()
                accounts.forEach { account ->
                    registerTokenLocked(accountId = account.id, token = token)
                        .onFailure { error ->
                            failures += account.id to error
                            Timber.e(error, "Failed to register FCM token for account %s", account.id)
                        }
                }

                if (failures.isEmpty()) {
                    Timber.d("FCM token registered with %d accounts", accounts.size)
                    Result.success(Unit)
                } else {
                    val summary = failures.joinToString(separator = "; ") {
                        "${it.first}=${it.second::class.simpleName}"
                    }
                    Result.failure(
                        IllegalStateException(
                            "FCM register-for-all failed for ${failures.size}/${accounts.size} accounts: $summary",
                            failures.first().second,
                        ),
                    )
                }
            }
        }

    override suspend fun registerToken(accountId: String, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            registrationMutex.withLock {
                registerTokenLocked(accountId = accountId, token = token)
            }
        }

    /**
     * Per-account register POST. **Caller MUST hold [registrationMutex]** —
     * this method does NOT acquire it, to avoid re-entry deadlock when called
     * from `registerTokenForAllAccounts` which already holds the lock.
     */
    private suspend fun registerTokenLocked(accountId: String, token: String): Result<Unit> =
        runCatching {
            val account = accountDao.getAccountById(accountId)
                ?: error("Account not found: $accountId")

            postToOdoo(
                serverUrl = account.fullServerUrl,
                path = REGISTER_PATH,
                params = mapOf(
                    PARAM_FCM_TOKEN to token,
                    PARAM_DEVICE_NAME to Build.MODEL,
                    PARAM_PLATFORM to PLATFORM_ANDROID,
                ),
                account = account,
            )
            Timber.d("FCM token registered for account %s", accountId)
        }.onFailure { error ->
            Timber.e(error, "Failed to register FCM token for account %s", accountId)
        }

    override suspend fun unregisterToken(accountId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            registrationMutex.withLock {
                runCatching {
                    val account = accountDao.getAccountById(accountId)
                        ?: error("Account not found: $accountId")
                    val token = encryptedPrefs.getFcmToken()
                        ?: error("No FCM token stored — nothing to unregister for account $accountId")

                    postToOdoo(
                        serverUrl = account.fullServerUrl,
                        path = UNREGISTER_PATH,
                        params = mapOf(PARAM_FCM_TOKEN to token),
                        account = account,
                    )
                    Timber.d("FCM token unregistered for account %s", accountId)
                }.onFailure { error ->
                    Timber.w(error, "FCM token unregister failed for account %s — proceeding with logout", accountId)
                }
            }
        }

    override fun getStoredToken(): String? = encryptedPrefs.getFcmToken()

    /**
     * Posts a JSON-RPC-style request to the Odoo push endpoint. The session cookie is
     * automatically attached by the [httpClient]'s CookieJar via [sessionCookieProvider].
     *
     * @throws IOException if the HTTP call fails or the response indicates a session error.
     */
    private fun postToOdoo(
        serverUrl: String,
        path: String,
        params: Map<String, String>,
        account: OdooAccount,
    ) {
        val url = "$serverUrl$path"
        val requestBody = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", "call")
            addProperty("id", 1)
            add("params", gson.toJsonTree(params))
        }

        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from $url")

        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw IOException("Session expired for account ${account.id} — token not registered at $url")
        }

        if (!response.isSuccessful) {
            throw IOException("Server error ${response.code} for $url")
        }

        // Check for application-level error in the Odoo JSON-RPC response
        runCatching {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val error = json.get("error")
            if (error != null && !error.isJsonNull) {
                throw IOException("Odoo error at $url: $error")
            }
        }.onFailure { parseError ->
            if (parseError is IOException) throw parseError
            // JSON parse failure is non-fatal — server returned 2xx which is enough
            Timber.w(parseError, "Could not parse Odoo response from %s", url)
        }
    }

    companion object {
        private const val REGISTER_PATH = "/woow_fcm_push/register"
        private const val UNREGISTER_PATH = "/woow_fcm_push/unregister"
        private const val PARAM_FCM_TOKEN = "fcm_token"
        private const val PARAM_DEVICE_NAME = "device_name"
        private const val PARAM_PLATFORM = "platform"
        private const val PLATFORM_ANDROID = "android"
        private const val TIMEOUT_SECONDS = 15L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * Provides session cookies for a given host. Abstracted as an interface so
 * [FcmTokenRepositoryImpl] can be unit-tested without the real [OdooJsonRpcClient].
 */
interface SessionCookieProvider {
    /** Returns the session cookies that should be sent to the given Odoo host. */
    fun getCookiesForHost(host: String): List<Cookie>
}
