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
    private val sessionCookieProvider: SessionCookieProvider = NoSessionCookies,
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
        httpClient = buildFcmHttpClient(sessionReauthInterceptor),
        sessionCookieProvider = sessionCookieProvider,
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
            // Story 8-2 (P0-2): register FAILS CLOSED. Registration is idempotent server-side
            // (the plugin's AC3 early return) and replays on the next cold start, so a false
            // failure costs one POST. A false success costs push, silently, forever — which is
            // exactly what this finding was. `Unreadable` is included: once we are reading the
            // body for a verdict, "2xx is enough" is the same silent-success hole in a new place.
            when (val outcome = FcmServerOutcome.parse(responseBody)) {
                is FcmServerOutcome.Rejected ->
                    throw IOException("Odoo rejected the registration for account $accountId (${outcome.detail})")
                FcmServerOutcome.Unreadable ->
                    throw IOException("Unreadable registration response for account $accountId")
                FcmServerOutcome.Ok -> Unit
            }
            Timber.d("FCM token registered for account %s", accountId)

            // Persist the opaque tenant id the server returns for this account so future push
            // notifications can be routed to it (multi-account deep-link isolation). Best-effort:
            // an older server that does not return a tenant id leaves the column null and the
            // account simply keeps current-behaviour routing until a newer server responds.
            val tenantId = FcmRegistrationResponse.parseTenantId(responseBody)
            if (tenantId != null && tenantId != account.tenantId) {
                // Story 8-1 (P2-9) WI-2: a colliding tenant id IS persisted — deliberately — and
                // logged. The first version of this refused to store it, which was backwards and
                // silently reintroduced the very mis-route WI-1 exists to prevent.
                //
                // `odoo_tenant_id` is the Odoo DATABASE NAME (see the plugin's `tenant_id_for`:
                // "one database == one tenant/box"), and spec §4.3 ships every STB box with the
                // same POSTGRES_DB unless an operator overrides it. Two unrelated servers therefore
                // routinely return an IDENTICAL id.
                //
                // `DeepLinkRouter` can only detect that ambiguity if BOTH accounts carry the id:
                // its guard is `matches.size > 1`. Refusing the second write leaves exactly one
                // owner, so the router sees a unique match and confidently switches to it — which
                // means a push from server Y opens server X's account. Storing it on both is what
                // makes the collision visible, and a visible collision is refused rather than
                // guessed.
                val collisions = accountDao.countAccountsWithTenantId(
                    tenantId = tenantId,
                    excludingId = accountId,
                )
                if (collisions > 0) {
                    Timber.w(
                        "Tenant id for account %s is shared with %d other account(s). Deep links " +
                            "for all of them will be DROPPED rather than mis-routed until the " +
                            "servers are given distinct tenant ids (or, for two users on one " +
                            "database, until the payload carries account identity)",
                        accountId, collisions,
                    )
                }
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

                    val body = postToOdoo(
                        serverUrl = account.fullServerUrl,
                        path = UNREGISTER_PATH,
                        params = mapOf(PARAM_FCM_TOKEN to token),
                        account = account,
                    )
                    // Story 8-2 (P0-2): unregister is deliberately MORE tolerant than register.
                    // Logout must never be blockable by a server, so an unreadable body proceeds;
                    // only an explicit rejection is a failure. Note `success: false` is NOT a
                    // rejection — it means the server had no row, which is the goal of unregister.
                    when (val outcome = FcmServerOutcome.parse(body)) {
                        is FcmServerOutcome.Rejected ->
                            throw IOException("Odoo rejected the unregister for account $accountId (${outcome.detail})")
                        FcmServerOutcome.Unreadable ->
                            Timber.w("Unreadable unregister response for account %s — proceeding", accountId)
                        FcmServerOutcome.Ok -> Unit
                    }
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

    override suspend fun reconcileOnAccountAvailable(): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Account-restored/added event (S2 / AC8.b). Read the CURRENT stored token and upsert it
            // for every logged-in account. No token yet (onNewToken has not fired) → no-op success;
            // the token-arrived event will register once a token exists. This closes the
            // token-arrived-before-account race: a token saved before any account existed is
            // registered here as soon as the account appears.
            val token = getStoredToken()
            if (token.isNullOrBlank()) {
                Timber.d("FCM account-available reconcile skipped — no token yet; token-arrived will register")
                return@withContext Result.success(Unit)
            }
            // Delegates to the existing upsert-per-account path. Idempotent server-side (register
            // early-returns an unchanged pair), so re-firing on every account event is cheap — no
            // diff-set / tri-state / canonical-state reconcile is kept.
            registerTokenForAllAccounts(token)
        }

    /**
     * Posts a JSON-RPC-style request to the Odoo push endpoint. The session cookie is
     * automatically attached by the [httpClient]'s CookieJar via [sessionCookieProvider].
     *
     * Returns the raw response body. It does NOT inspect the body for an application-level
     * rejection — see the note at the return statement.
     *
     * @throws IOException if the HTTP call fails, the session is expired, or the status is non-2xx.
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

        // Story 8-2 (P0-3): attach THIS account's session explicitly. See buildFcmHttpClient for
        // why a CookieJar cannot do this and why the jar must stay empty. The account id is also
        // stamped as a request tag so SessionReauthInterceptor can re-authenticate the right
        // account instead of guessing from the host.
        val cookies = sessionCookieProvider.getCookiesForAccount(account.id)
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .apply {
                if (cookies.isNotEmpty()) {
                    header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                }
            }
            .tag(FcmRequestAccount::class.java, FcmRequestAccount(account.id))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from $url")

        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw IOException("Session expired for account ${account.id} — token not registered at $url")
        }

        if (!response.isSuccessful) {
            throw IOException("Server error ${response.code} for $url")
        }

        // Story 8-2 (P0-2): this function deliberately does NOT decide whether the SERVER
        // rejected us. It reports transport outcomes (401, non-2xx, empty body) and returns
        // the body; the callers classify it with `FcmServerOutcome.parse` and apply their own
        // severity policy. The two endpoints have genuinely different safe defaults — register
        // must fail closed, unregister must never be blockable — and a shared transport helper
        // applying one rule to both is what let a rejected registration read as success.
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
        /**
         * ⚠️ **`CookieJar.NO_COOKIES` is load-bearing, not tidiness.**
         *
         * The session cookie is now attached explicitly per request by [postToOdoo], because a
         * `CookieJar` cannot do it correctly: `loadForRequest(url)` receives only the URL and can
         * never know which ACCOUNT a request belongs to (story 8-2, P0-3).
         *
         * And a non-empty jar would silently defeat that: OkHttp's `BridgeInterceptor` runs AFTER
         * application interceptors and calls `requestBuilder.header("Cookie", …)`, which
         * **replaces** rather than appends. Any jar returning cookies here would overwrite the
         * per-account header on the way to the wire, and — worse — an application interceptor in a
         * test would still observe the correct header, so the bug would be invisible to the obvious
         * assertion. Do not put a jar back on this client.
         */
        internal fun buildFcmHttpClient(
            sessionReauthInterceptor: SessionReauthInterceptor,
        ): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor(sessionReauthInterceptor)
            .build()
    }
}

/**
 * Provides session cookies for a given host. Abstracted as an interface so
 * [FcmTokenRepositoryImpl] can be unit-tested without the real [OdooJsonRpcClient].
 */
/**
 * Identifies the account a push register/unregister request belongs to, carried as an OkHttp request
 * tag. An `Interceptor` can read a tag (it sees the `Request`); a `CookieJar` cannot (it sees only
 * the URL), which is why the account travels this way rather than through the jar.
 */
data class FcmRequestAccount(val accountId: String)

/** No session at all. The default for the test constructor, so a test opts in to cookies. */
object NoSessionCookies : SessionCookieProvider {
    override fun getCookiesForAccount(accountId: String): List<Cookie> = emptyList()
}

interface SessionCookieProvider {
    /**
     * Returns the session cookies for [accountId].
     *
     * Keyed by ACCOUNT, not host (story 8-2, P0-3). A host lookup could only ever return one
     * session, so on a server with two logged-in accounts every registration POST went out under
     * whichever session happened to survive — and the second account never got a device row while
     * the app reported success for both.
     */
    fun getCookiesForAccount(accountId: String): List<Cookie>
}

/**
 * Parses the opaque tenant id out of an Odoo FCM device-registration response. Isolated as a pure
 * function so it can be unit-tested without any network layer.
 */
/**
 * What the Odoo server said about a push register/unregister request, read from the JSON-RPC body.
 *
 * Every route in `woow_fcm_push` is `type='json'` and returns HTTP **200** even for validation
 * failures, putting the failure INSIDE `result` (`controllers/fcm_controller.py:39,43,46,74`). The
 * client used to inspect only the JSON-RPC ENVELOPE `error`, so `"Invalid fcm_token format"` was
 * logged as *registered* and returned as `Result.success` — with no retry until the next cold start
 * and no release-mode logging to notice (P0-2).
 */
sealed interface FcmServerOutcome {

    /** The server accepted the request. */
    data object Ok : FcmServerOutcome

    /** The server reached us and said no. [detail] is for logging only, never for logic. */
    data class Rejected(val detail: String) : FcmServerOutcome

    /** The body could not be read as a JSON-RPC response at all. Callers decide what that means. */
    data object Unreadable : FcmServerOutcome

    companion object {

        private val parser = Gson()

        /**
         * Classifies a JSON-RPC response body.
         *
         * Rejection is keyed on the **presence of an `error` key**, at the envelope level or inside
         * `result`, and never on its message text: the plugin's own tests assert only
         * `assertIn('error', result)`, so the strings are not a contract we may depend on.
         *
         * `success: false` from `unregister` is deliberately **[Ok]**. It means "you had no row",
         * which after a logout is the DESIRED end state — treating it as a rejection would
         * manufacture an alarm on every correct logout. That is why this parser reads only `error`
         * and ignores `success` entirely, for both endpoints.
         */
        fun parse(responseBody: String?): FcmServerOutcome {
            if (responseBody.isNullOrBlank()) return Unreadable
            val root = runCatching { parser.fromJson(responseBody, JsonObject::class.java) }
                .getOrNull() ?: return Unreadable

            root.get("error")?.takeUnless { it.isJsonNull }?.let {
                return Rejected("envelope error: $it")
            }

            val result = runCatching { root.getAsJsonObject("result") }.getOrNull()
                ?: return Unreadable

            result.get("error")?.takeUnless { it.isJsonNull }?.let {
                return Rejected("result error: $it")
            }

            return Ok
        }
    }
}

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
