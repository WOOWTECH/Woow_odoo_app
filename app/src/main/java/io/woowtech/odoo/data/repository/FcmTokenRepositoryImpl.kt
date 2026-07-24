package io.woowtech.odoo.data.repository

import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.woowtech.odoo.data.api.SessionReauthInterceptor
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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers and unregisters FCM tokens with Odoo servers via HTTP POST
 * to `/woow_fcm_push/register` and `/woow_fcm_push/unregister` endpoints.
 * Stores token locally in EncryptedPrefs.
 *
 * Registration uses the current Odoo session cookie for authentication — the Odoo
 * module requires `auth='user'` so the cookie must be present. The register/unregister
 * routes are `type='json'`, so Odoo signals an expired session as **HTTP 200 with a
 * JSON-RPC `SessionExpiredException` error envelope**, not HTTP 401. The injected
 * [SessionReauthInterceptor] detects that envelope (and a genuine 401), transparently
 * re-authenticates once with the account's stored credentials against its own https
 * host, refreshes the cookie, and replays the request (WI-3, see
 * [io.woowtech.odoo.data.api.SessionReauthenticator]). Only a *persistent* session
 * expiry that survives that single re-auth+retry surfaces here as an [IOException]; the
 * failure is then logged and the token is re-registered on the next successful auth.
 *
 * Transient errors (5xx, network timeout) are returned as [Result.failure] so the
 * caller can apply retry logic if needed.
 */
@Singleton
class FcmTokenRepositoryImpl(
    private val encryptedPrefs: EncryptedPrefs,
    private val accountDao: AccountDao,
    private val httpClient: OkHttpClient,
) : FcmTokenRepository {

    /**
     * Production entry point. Hilt injects the collaborators; we build the hardened OkHttp client
     * (cookie jar + WI-3 re-auth interceptor). The primary constructor takes the client directly so
     * unit tests can inject a [okhttp3.mockwebserver.MockWebServer]-backed client and drive real
     * success / hard-failure / unreachable outcomes hermetically.
     */
    @Inject
    constructor(
        encryptedPrefs: EncryptedPrefs,
        accountDao: AccountDao,
        sessionCookieProvider: SessionCookieProvider,
        sessionReauthInterceptor: SessionReauthInterceptor,
    ) : this(
        encryptedPrefs = encryptedPrefs,
        accountDao = accountDao,
        httpClient = buildFcmHttpClient(sessionCookieProvider, sessionReauthInterceptor),
    )

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

    override suspend fun registerTokenForAllAccounts(token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            registrationMutex.withLock {
                val oldToken = encryptedPrefs.getFcmToken()
                val accounts = accountDao.getAllAccountsList()

                // MA-1 rotation-unregister: if Firebase rotated the token, unregister the
                // OLD token from every account BEFORE overwriting it, so no "ghost" device
                // row survives in any Odoo DB (FR-MA-4). unregisterToken() reads the current
                // stored token, so the old value must be unregistered explicitly here, before
                // saveFcmToken overwrites it. Best-effort per account — never blocks the new
                // token's registration.
                if (oldToken != null && oldToken != token) {
                    accounts.forEach { account ->
                        runCatching {
                            postToOdoo(
                                serverUrl = account.fullServerUrl,
                                path = UNREGISTER_PATH,
                                params = mapOf(PARAM_FCM_TOKEN to oldToken),
                                account = account,
                            )
                        }.onFailure { error ->
                            Timber.w(error, "Failed to unregister rotated token for account %s", account.id)
                        }
                    }
                }

                encryptedPrefs.saveFcmToken(token)

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
                            // Coroutine cancellation must propagate, never be collected as a
                            // per-account failure (CLAUDE.md: "Never catch CancellationException").
                            if (error is CancellationException) throw error
                            failures += account.id to error
                        }
                }

                // Classify by whether the SERVER WAS REACHED. A genuine connectivity failure — DNS
                // no longer resolves (retired tenant), connection refused, or a connect/read timeout —
                // is best-effort: registration self-heals on the next launch / onNewToken, so it must
                // NOT poison the whole fan-out (a dead account lingering in the DB otherwise turned
                // every reconcile into a scary IllegalStateException).
                //
                // Everything else is a HARD failure the caller SHOULD see: postToOdoo maps HTTP 401,
                // non-2xx, and Odoo error envelopes to IOException too, and those mean the server WAS
                // reached and rejected us — swallowing them would hide real auth/server problems. So
                // the classifier is the specific unreachable exception types, NOT `is IOException`.
                val (unreachable, hard) = failures.partition { it.second.isUnreachable() }
                unreachable.forEach { (id, error) ->
                    Timber.w(error, "FCM register skipped for unreachable account %s — best-effort, will retry", id)
                }
                hard.forEach { (id, error) ->
                    Timber.e(error, "Failed to register FCM token for account %s", id)
                }
                val okCount = accounts.size - failures.size

                when {
                    hard.isNotEmpty() -> {
                        val summary = hard.joinToString(separator = "; ") {
                            "${it.first}=${it.second::class.simpleName}"
                        }
                        Result.failure(
                            IllegalStateException(
                                "FCM register-for-all failed for ${hard.size}/${accounts.size} accounts: $summary",
                                hard.first().second,
                            ),
                        )
                    }
                    okCount == 0 && unreachable.isNotEmpty() -> {
                        // Nothing registered anywhere — every account was unreachable (e.g. the app
                        // launched offline). Not a hard error, but the caller must know zero accounts
                        // got the token so it retries rather than treating this as done.
                        Timber.w("FCM token registered with 0 accounts — all %d unreachable; will retry", unreachable.size)
                        Result.failure(
                            IOException("all ${unreachable.size} account(s) unreachable — token not registered anywhere"),
                        )
                    }
                    else -> {
                        Timber.d(
                            "FCM token registered (%d ok, %d unreachable) of %d accounts",
                            okCount,
                            unreachable.size,
                            accounts.size,
                        )
                        Result.success(Unit)
                    }
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
        try {
            val account = accountDao.getAccountById(accountId)
                ?: error("Account not found: $accountId")

            val responseBody = postToOdoo(
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

            // Persist the opaque tenant id the server returns for this account so future push
            // notifications can be routed to it (multi-account deep-link isolation). Best-effort:
            // an older server that does not return a tenant id leaves the column null and the
            // account simply keeps current-behaviour routing until a newer server responds.
            val tenantId = FcmRegistrationResponse.parseTenantId(responseBody)
            if (tenantId != null && tenantId != account.tenantId) {
                accountDao.updateTenantId(id = accountId, tenantId = tenantId)
                Timber.d("Persisted tenant id for account %s", accountId)
            }
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            // Never swallow cancellation — let it propagate so the coroutine cancels cleanly.
            throw cancellation
        } catch (error: Throwable) {
            // The caller classifies (reachable vs hard) and logs at the appropriate level.
            Result.failure(error)
        }

    /**
     * True only when [this] means the server could not be reached at all — so registration is
     * genuinely best-effort and self-heals on the next retry. [postToOdoo] maps HTTP 401, non-2xx,
     * and Odoo error envelopes to a plain [IOException] (the server WAS reached and rejected us),
     * so those are deliberately EXCLUDED here and treated as hard failures the caller must see.
     */
    private fun Throwable.isUnreachable(): Boolean =
        this is UnknownHostException || this is ConnectException || this is SocketTimeoutException

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

    override suspend fun reconcileToken(token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Short-circuit when no account is logged in: nothing to register the token against.
            // AccountRepository replays the saved token on the next login/switch. No server call.
            if (accountDao.getAllAccountsList().isEmpty()) {
                Timber.d("FCM reconcile: no active accounts — nothing to register")
                return@withContext Result.success(Unit)
            }
            registerTokenForAllAccounts(token)
        }

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
    ): String {
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

        return responseBody
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

        /**
         * Builds the hardened OkHttp client used for register/unregister POSTs: per-account session
         * cookies via [sessionCookieProvider], and the WI-3 [SessionReauthInterceptor] that
         * transparently re-authenticates an expired Odoo session (HTTP 200 JSON-RPC
         * SessionExpiredException envelope, or a genuine 401) once and replays the request — all
         * safety guardrails live in SessionReauthenticator.
         */
        private fun buildFcmHttpClient(
            sessionCookieProvider: SessionCookieProvider,
            sessionReauthInterceptor: SessionReauthInterceptor,
        ): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    sessionCookieProvider.getCookiesForHost(url.host)
            })
            .addInterceptor(sessionReauthInterceptor)
            .build()
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

/**
 * Parses the opaque tenant id out of an Odoo FCM device-registration response. Isolated as a pure
 * function so it can be unit-tested without any network layer.
 */
object FcmRegistrationResponse {

    private val tenantIdKeys = listOf("tenant_id", "odoo_tenant_id")
    private val parser = Gson()

    /**
     * Returns the tenant id from a JSON-RPC registration [responseBody], or null if the response is
     * malformed or carries no tenant id (older server). The id is read from the `result` object,
     * accepting either a `tenant_id` or `odoo_tenant_id` key, and only non-blank string/number
     * values are accepted.
     */
    fun parseTenantId(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        return runCatching {
            val root = parser.fromJson(responseBody, JsonObject::class.java) ?: return null
            val result = root.getAsJsonObject("result") ?: return null
            for (key in tenantIdKeys) {
                val element = result.get(key)
                if (element != null && element.isJsonPrimitive) {
                    val value = element.asString
                    if (value.isNotBlank()) return value
                }
            }
            null
        }.getOrNull()
    }
}
