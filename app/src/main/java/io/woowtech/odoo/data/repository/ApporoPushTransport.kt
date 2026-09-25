package io.woowtech.odoo.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Apporo-only sessions. Never reads/writes OdooJsonRpcClient's host jar: two users/DBs on the
 * same origin must not borrow each other's capability or credentials. Capability is not cached.
 * A single account lock spans auth + cap + write, with at most one session-heal per operation.
 */
@Singleton
class ApporoPushTransport internal constructor(
    private val accountDao: AccountDao,
    private val encryptedPrefs: EncryptedPrefs,
    private val reloginSignal: ReloginSignal,
    client: OkHttpClient,
) {
    @Inject
    constructor(accountDao: AccountDao, encryptedPrefs: EncryptedPrefs, reloginSignal: ReloginSignal) :
        this(accountDao, encryptedPrefs, reloginSignal, OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build())

    // NO_COOKIES is essential: BridgeInterceptor must not overwrite the explicit account cookie.
    // No automatic redirects: neither credentials nor token may escape the stored origin.
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).build()
    private val gson = Gson()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val openCircuits = ConcurrentHashMap.newKeySet<String>()
    private val missingManualSessions = ConcurrentHashMap.newKeySet<String>()

    private data class Identity(
        val id: String, val origin: String, val database: String, val username: String, val userId: Int?,
    )
    private data class Session(val identity: Identity, val cookie: String)
    private class SessionExpired : IOException("Push session expired")

    private fun identity(account: OdooAccount): Identity {
        val url = account.serverUrl.toHttpUrlOrNull()
            ?: throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
        return Identity(account.id, url.toString().trimEnd('/'), account.database, account.username, account.userId)
    }

    private suspend fun requireCurrent(expected: Identity, capturedCleanup: Boolean = false) {
        val current = accountDao.getAccountById(expected.id)
        // Explicit cleanup may finish with its captured SID after local removal; never re-auth
        // a removed account and never resolve a replacement by host/active account.
        if (current == null && capturedCleanup) return
        if (current == null || identity(current) != expected || encryptedPrefs.getPassword(expected.id).isNullOrEmpty()) {
            sessions.remove(expected.id)
            throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
        }
    }

    suspend fun write(account: OdooAccount, path: String, params: Map<String, String>): String =
        locks.getOrPut(account.id) { Mutex() }.withLock {
            val expected = identity(account)
            if (account.id in missingManualSessions) throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
            val cached = sessions[account.id]?.takeIf { it.identity == expected }
            val cleanup = path == "/woow_fcm_push/unregister"
            requireCurrent(expected, capturedCleanup = cleanup && cached != null)
            var session = cached ?: authenticate(expected)
            var healed = false
            while (true) {
                try {
                    requireCurrent(expected, capturedCleanup = cleanup)
                    val capabilities = post(expected.origin, ApporoPushContract.CAPABILITIES_PATH, emptyMap(), session.cookie)
                    ApporoPushContract.requireCapabilities(capabilities.body)
                    requireCurrent(expected, capturedCleanup = cleanup)
                    val result = post(expected.origin, path, params + ("app_brand" to ApporoPushContract.BRAND), session.cookie)
                    requireCurrent(expected, capturedCleanup = cleanup)
                    return@withLock result.body
                } catch (_: SessionExpired) {
                    sessions.remove(account.id)
                    if (healed) throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
                    healed = true
                    requireCurrent(expected)
                    session = authenticate(expected)
                    // Never replay a write directly after auth; repeat capability in the new session.
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }

    /** Manual login resets only this account; discard its old push session as well. */
    suspend fun onManualLogin(accountId: String, sessionId: String?) = locks.getOrPut(accountId) { Mutex() }.withLock {
        clearAccount(accountId)
        val account = accountDao.getAccountById(accountId)
        if (account == null || sessionId.isNullOrBlank() || sessionId.any { it <= ' ' || it == ';' || it >= '\u007f' }) {
            missingManualSessions.add(accountId)
        } else {
            val expected = identity(account)
            requireCurrent(expected)
            sessions[accountId] = Session(expected, "session_id=$sessionId")
        }
        Unit
    }

    suspend fun forgetAccount(accountId: String) = locks.getOrPut(accountId) { Mutex() }.withLock {
        clearAccount(accountId)
    }

    private fun clearAccount(accountId: String) {
        sessions.remove(accountId)
        failures.remove(accountId)
        openCircuits.remove(accountId)
        missingManualSessions.remove(accountId)
    }

    private suspend fun authenticate(expected: Identity): Session {
        requireCurrent(expected)
        if (expected.id in openCircuits) throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
        val password = encryptedPrefs.getPassword(expected.id)
            ?: throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
        val response = try {
            post(expected.origin, "/web/session/authenticate", mapOf(
                "db" to expected.database, "login" to expected.username, "password" to password,
            ), null)
        } catch (error: IOException) {
            authFailed(expected.id, invalidCredentials = error is SessionExpired)
            if (error is SessionExpired) throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
            throw error
        }
        val root = runCatching { gson.fromJson(response.body, JsonObject::class.java) }.getOrNull()
        val error = runCatching { root?.getAsJsonObject("error") }.getOrNull()
        val result = runCatching { root?.getAsJsonObject("result") }.getOrNull()
        if (error != null || result == null) {
            val invalid = runCatching {
                error?.getAsJsonObject("data")?.get("name")?.asString == "odoo.exceptions.AccessDenied"
            }.getOrDefault(false)
            authFailed(expected.id, invalidCredentials = invalid)
            throw PushContractException(if (invalid) PushRegistrationStatus.SIGN_IN_REQUIRED else PushRegistrationStatus.RETRY_NEEDED)
        }
        val uid = runCatching { result.get("uid")?.asInt }.getOrNull()
        if (uid == null || uid <= 0 || uid != expected.userId) {
            // A rejected identity must not be retried with a different account or host.
            authFailed(expected.id, invalidCredentials = true)
            throw PushContractException(PushRegistrationStatus.SIGN_IN_REQUIRED)
        }
        val cookie = response.sessionCookie ?: run {
            authFailed(expected.id, invalidCredentials = false)
            throw PushContractException(PushRegistrationStatus.RETRY_NEEDED)
        }
        requireCurrent(expected)
        failures.remove(expected.id)
        return Session(expected, cookie).also { sessions[expected.id] = it }
    }

    private fun authFailed(accountId: String, invalidCredentials: Boolean) {
        sessions.remove(accountId)
        val count = failures.merge(accountId, 1, Int::plus) ?: 1
        if (invalidCredentials || count >= 3) {
            openCircuits.add(accountId)
            reloginSignal.request(accountId, if (invalidCredentials) ReloginReason.INVALID_CREDENTIALS else ReloginReason.REAUTH_CIRCUIT_OPEN)
        }
    }

    private data class Reply(val body: String, val sessionCookie: String?)

    private fun post(origin: String, path: String, params: Map<String, String>, cookie: String?): Reply {
        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", "call")
            addProperty("id", 1)
            add("params", gson.toJsonTree(params))
        }
        val request = Request.Builder().url(origin + path)
            .post(gson.toJson(envelope).toRequestBody("application/json".toMediaType()))
            .apply { if (cookie != null) header("Cookie", cookie) }.build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty push response")
            val error = runCatching {
                gson.fromJson(body, JsonObject::class.java)?.getAsJsonObject("error")
            }.getOrNull()
            val sessionExpired = runCatching {
                error?.getAsJsonObject("data")?.get("name")?.asString == "odoo.http.SessionExpiredException" ||
                    (error?.get("code")?.asInt == 100 &&
                        error.get("message")?.asString.orEmpty().contains("session expired", ignoreCase = true))
            }.getOrDefault(false)
            if (response.code == 401 || sessionExpired) throw SessionExpired()
            if (response.code == 404 && path == ApporoPushContract.CAPABILITIES_PATH) {
                throw PushContractException(PushRegistrationStatus.NOT_CONFIGURED)
            }
            if (!response.isSuccessful) throw IOException("Push HTTP ${response.code}")
            val sessionCookie = Cookie.parseAll(request.url, response.headers)
                .firstOrNull { it.name == "session_id" && it.value.isNotBlank() && it.matches(request.url) && it.expiresAt > System.currentTimeMillis() }
                ?.let { "session_id=${it.value}" }
            Reply(body, sessionCookie)
        }
    }
}
