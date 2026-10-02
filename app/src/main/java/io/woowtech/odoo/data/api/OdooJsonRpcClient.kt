package io.woowtech.odoo.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import io.woowtech.odoo.BuildConfig
import io.woowtech.odoo.brand.AppBrand
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OdooJsonRpcClient internal constructor(
    apporoAuthClient: OkHttpClient,
    private val brand: AppBrand,
    sharedAuthClient: OkHttpClient? = null,
) {
    @Inject
    constructor() : this(OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).build(), AppBrand.current)

    private val isolatedAuthClient = apporoAuthClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()

    private val gson = Gson()
    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        // pi 0930b P1: a sign-in response's cookies are kept only once that sign-in succeeded (see
        // [authenticate]); a failed or cookie-less answer never replaces another account's session.
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

        // 1001 (demo111 B4): a sign-in never sends another account's session_id; Odoo would
        // re-authenticate that session as the new user and rotate it away.
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    private val client: OkHttpClient = (sharedAuthClient?.newBuilder() ?: OkHttpClient.Builder())
        .cookieJar(cookieJar)
        // pi 1001b P1: a sign-in never follows a redirect (Apporo isolated parity); a 3xx is a failure.
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        })
        .build()

    fun getSessionCookies(host: String): List<Cookie> {
        return cookieStore[host] ?: emptyList()
    }

    fun getSessionId(host: String): String? {
        return cookieStore[host]?.find { it.name == "session_id" }?.value
    }

    fun clearCookies(host: String) {
        cookieStore.remove(host)
    }

    suspend fun authenticate(
        serverUrl: String,
        database: String,
        username: String,
        password: String
    ): AuthResult = withContext(Dispatchers.IO) {
        if (brand.isApporo) {
            val result = authenticateApporo(serverUrl, database, username, password)
            // Shared WebView reauth retains its publish-on-success contract.
            if (result is AuthResult.Success) publishApporoSession(serverUrl, result.sessionId)
            return@withContext result
        }
        try {
            if (!serverUrl.startsWith("https://")) {
                return@withContext AuthResult.Error(
                    "HTTPS required",
                    AuthResult.ErrorType.HTTPS_REQUIRED
                )
            }

            val url = "$serverUrl/web/session/authenticate"
            val requestBody = JsonRpcRequest(
                jsonrpc = "2.0",
                method = "call",
                params = mapOf(
                    "db" to database,
                    "login" to username,
                    "password" to password
                ),
                id = 1
            )

            val signIn = executeRequest(url, requestBody)
            val response = signIn.body

            if (response.error != null) {
                val errorMessage = response.error.data?.message
                    ?: response.error.message
                    ?: "Authentication failed"

                return@withContext when {
                    isAccessDenied(response.error.data?.name, errorMessage) ->
                        AuthResult.Error(errorMessage, AuthResult.ErrorType.INVALID_CREDENTIALS)
                    errorMessage.contains("database", ignoreCase = true) ->
                        AuthResult.Error(errorMessage, AuthResult.ErrorType.DATABASE_NOT_FOUND)
                    errorMessage.contains("login", ignoreCase = true) ||
                            errorMessage.contains("password", ignoreCase = true) ||
                            errorMessage.contains("credentials", ignoreCase = true) ->
                        AuthResult.Error(errorMessage, AuthResult.ErrorType.INVALID_CREDENTIALS)
                    else ->
                        AuthResult.Error(errorMessage, AuthResult.ErrorType.SERVER_ERROR)
                }
            }

            val result = response.result
                ?: return@withContext AuthResult.Error("Invalid sign-in response", AuthResult.ErrorType.SERVER_ERROR)
            if (!result.has("uid") || result.get("uid").isJsonNull) {
                return@withContext AuthResult.Error(
                    "Invalid credentials",
                    AuthResult.ErrorType.INVALID_CREDENTIALS
                )
            }

            val uid = result.get("uid").asInt
            // pi 0930b P1 (iOS isolated response-cookie rule): only THIS response's session counts; the
            // host jar may still hold another account's session, which must never become this sign-in's.
            val sessionId = signIn.sessionId
                ?: return@withContext AuthResult.Error("Sign-in session was not established", AuthResult.ErrorType.SESSION_EXPIRED)
            // pi 1001f P1: nothing is published here; the repository publishes at its selection commit.
            val name = result.get("name")?.asString ?: username

            AuthResult.Success(
                userId = uid,
                sessionId = sessionId,
                username = username,
                displayName = name
            )
        } catch (e: SignInHttpStatusException) {
            signInHttpStatus(e.code)
        } catch (e: InvalidSignInResponseException) {
            AuthResult.Error("Invalid sign-in response", AuthResult.ErrorType.SERVER_ERROR)
        } catch (e: UnknownHostException) {
            AuthResult.Error("Unable to connect to server", AuthResult.ErrorType.NETWORK_ERROR)
        } catch (e: SocketTimeoutException) {
            AuthResult.Error("Connection timeout", AuthResult.ErrorType.NETWORK_ERROR)
        } catch (e: IOException) {
            AuthResult.Error("Network error: ${e.message}", AuthResult.ErrorType.NETWORK_ERROR)
        } catch (e: IllegalArgumentException) {
            // OkHttp 對無法解析的網址（例如主機名稱含空白）丟 IllegalArgumentException
            AuthResult.Error("Invalid server URL", AuthResult.ErrorType.INVALID_URL)
        } catch (e: Exception) {
            AuthResult.Error("Error: ${e.message}", AuthResult.ErrorType.UNKNOWN)
        }
    }

    /** Apporo login reads only THIS response's SID; a concurrent host login cannot supply it. */
    internal suspend fun authenticateApporoIsolated(
        serverUrl: String, database: String, username: String, password: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        check(brand.isApporo)
        authenticateApporo(serverUrl, database, username, password)
    }

    /**
     * pi 1001d P1: the self-heal sign-in (both brands) reads only THIS response's session and never touches the
     * shared jar; [SessionReauthenticator] publishes it only after the account's fence admits it.
     */
    internal suspend fun authenticateForSelfHeal(
        serverUrl: String, database: String, username: String, password: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        authenticateApporo(serverUrl, database, username, password)
    }

    private fun authenticateApporo(serverUrl: String, database: String, username: String, password: String): AuthResult {
        return try {
            val url = serverUrl.toHttpUrlOrNull()
            if (url == null && serverUrl.startsWith("https://")) {
                return AuthResult.Error("Invalid server URL", AuthResult.ErrorType.INVALID_URL)
            }
            if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null
            ) return AuthResult.Error("HTTPS origin required", AuthResult.ErrorType.HTTPS_REQUIRED)
            val request = Request.Builder().url(url.newBuilder().addPathSegments("web/session/authenticate").build())
                .post(gson.toJson(JsonRpcRequest(method = "call", params = mapOf(
                    "db" to database, "login" to username, "password" to password,
                ), id = 1)).toRequestBody("application/json".toMediaType())).build()
            isolatedAuthClient.newCall(request).execute().use { response ->
                if (response.code != 200) return signInHttpStatus(response.code)
                val envelope = gson.fromJson(response.body?.string(), JsonObject::class.java)
                    ?: return AuthResult.Error("Invalid sign-in response", AuthResult.ErrorType.SERVER_ERROR)
                val error = envelope.getAsJsonObject("error")
                if (error != null) {
                    val data = error.getAsJsonObject("data")
                    val message = data?.get("message")?.asString ?: error.get("message")?.asString
                    val denied = isAccessDenied(data?.get("name")?.asString, message)
                    return AuthResult.Error("Sign-in request rejected", if (denied) AuthResult.ErrorType.INVALID_CREDENTIALS else AuthResult.ErrorType.SERVER_ERROR)
                }
                val result = envelope.getAsJsonObject("result")
                    ?: return AuthResult.Error("Invalid sign-in response", AuthResult.ErrorType.SERVER_ERROR)
                val uid = runCatching { result.get("uid")?.asInt }.getOrNull()
                if (uid == null || uid <= 0) return AuthResult.Error("Invalid credentials", AuthResult.ErrorType.INVALID_CREDENTIALS)
                val sid = Cookie.parseAll(request.url, response.headers)
                    .firstOrNull { it.name == "session_id" && it.matches(request.url) && it.expiresAt > System.currentTimeMillis() }
                    ?.value.orEmpty()
                if (sid.isBlank() || sid.any { it <= ' ' || it == ';' || it >= '\u007f' }) return AuthResult.Error("Sign-in session was not established", AuthResult.ErrorType.SESSION_EXPIRED)
                AuthResult.Success(uid, sid, username, result.get("name")?.asString ?: username)
            }
        } catch (_: IOException) {
            AuthResult.Error("Sign-in network error", AuthResult.ErrorType.NETWORK_ERROR)
        } catch (_: Exception) {
            AuthResult.Error("Invalid sign-in response", AuthResult.ErrorType.SERVER_ERROR)
        }
    }

    /** Shared reauth or the manual selection commit may publish; isolated auth/push heal never do. */
    internal fun publishApporoSession(serverUrl: String, sessionId: String) {
        check(brand.isApporo)
        publishSession(serverUrl, sessionId)
    }

    /**
     * Puts a proven [sessionId] into the native jar for [serverUrl]'s host (both brands). Callers must
     * have proven it is the account's own live session; a WOOW promotion uses it after a logout so the
     * promoted account is not signed in again (pi 0930b Android P2).
     */
    internal fun publishSession(serverUrl: String, sessionId: String) {
        val url = serverUrl.toHttpUrlOrNull() ?: return
        require(sessionId.isNotBlank()) { "A proven session is required" }
        val cookie = Cookie.Builder().name("session_id").value(sessionId)
            .hostOnlyDomain(url.host).path("/").secure().httpOnly().build()
        cookieStore[url.host] = mutableListOf(cookie)
    }

    /**
     * Whether [sessionId] is still a live Odoo session of [userId] on [database] (iOS D5 parity: an
     * account switch reuses such a session instead of signing in again). Fails closed — any transport
     * error, a non-200 answer, or a missing / different `uid` or `db` is `false` — so an unproven
     * session is never handed to the WebView. Uses a cookie-less, non-redirecting client: nothing is
     * published and no jar is touched.
     */
    internal suspend fun sessionBelongsTo(serverUrl: String, sessionId: String, userId: Int, database: String): Boolean =
        sessionOwnership(serverUrl, sessionId, userId, database) == SessionOwnership.Belongs

    /**
     * pi 1001c P1: what the server PROVES about [sessionId]. Only [SessionOwnership.Belongs] and
     * [SessionOwnership.ProvenMismatch] are evidence; offline, timeout, non-200, an unparsable answer or a
     * missing db prove nothing ([SessionOwnership.Unknown]) and must never authorize a revoke.
     */
    internal suspend fun sessionOwnership(serverUrl: String, sessionId: String, userId: Int, database: String): SessionOwnership =
        withContext(Dispatchers.IO) {
            val envelope = postWithSession(serverUrl, "web/session/get_session_info", sessionId)
                ?: return@withContext SessionOwnership.Unknown
            val error = envelope.get("error") as? JsonObject
            if (error != null) {
                val name = runCatching { (error.get("data") as? JsonObject)?.get("name")?.asString }.getOrNull()
                // The server says this session is dead: it is nobody's live session any more.
                return@withContext if (name == SESSION_EXPIRED) SessionOwnership.ProvenMismatch else SessionOwnership.Unknown
            }
            val result = envelope.get("result") as? JsonObject ?: return@withContext SessionOwnership.Unknown
            val uid = runCatching { result.get("uid")?.takeUnless { it.isJsonNull }?.asInt }.getOrNull()
            val db = runCatching { result.get("db")?.takeUnless { it.isJsonNull }?.asString }.getOrNull()
            when {
                // pi 1001d P2: a missing or unparseable uid/db proves nothing; only explicit values decide.
                db.isNullOrEmpty() || uid == null -> SessionOwnership.Unknown
                uid == userId && db == database -> SessionOwnership.Belongs
                else -> SessionOwnership.ProvenMismatch
            }
        }

    /**
     * Best-effort server-side logout of [sessionId] (`/web/session/destroy`, iOS D1 parity). Never
     * throws; true only when the server answered with a JSON-RPC envelope. Same isolated client as
     * [sessionBelongsTo].
     */
    internal suspend fun revokeSession(serverUrl: String, sessionId: String): Boolean = withContext(Dispatchers.IO) {
        postWithSession(serverUrl, "web/session/destroy", sessionId) != null
    }

    private val sessionCallClient by lazy { isolatedAuthClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

    private fun postWithSession(serverUrl: String, path: String, sessionId: String): JsonObject? = try {
        val url = serverUrl.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null ||
            sessionId.isBlank() || sessionId.any { it <= ' ' || it == ';' || it >= '\u007f' }
        ) {
            null
        } else {
            val request = Request.Builder().url(url.newBuilder().addPathSegments(path).build())
                .header("Cookie", "session_id=$sessionId")
                .post(gson.toJson(JsonRpcRequest(method = "call", params = emptyMap(), id = 1))
                    .toRequestBody("application/json".toMediaType()))
                .build()
            sessionCallClient.newCall(request).execute().use { response ->
                if (response.code != 200) null else gson.fromJson(response.body?.string(), JsonObject::class.java)
            }
        }
    } catch (_: Exception) {
        null
    }

    /** A 200 sign-in body that is not a JSON-RPC envelope (e.g. a proxy HTML page): localized server error. */
    private class InvalidSignInResponseException : Exception("Invalid sign-in response")

    /** Odoo 18 answers a wrong password with `odoo.exceptions.AccessDenied` / "Access Denied" (iOS D2 parity). */
    private fun isAccessDenied(name: String?, message: String?): Boolean =
        name == ACCESS_DENIED || message.orEmpty().contains("Access Denied", ignoreCase = true)

    private companion object {
        const val ACCESS_DENIED = "odoo.exceptions.AccessDenied"
        const val SESSION_EXPIRED = "odoo.http.SessionExpiredException"
    }

    /** A parsed sign-in envelope plus THIS response's live cookies and valid `session_id` (null when none). */
    private class SignInResponse(val body: JsonRpcResponse, val host: String, val cookies: List<Cookie>, val sessionId: String?)

    private fun executeRequest(url: String, body: JsonRpcRequest): SignInResponse {
        val jsonBody = gson.toJson(body)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (response.code != 200) {
            response.close()
            throw SignInHttpStatusException(response.code)
        }
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        // pi 1001b P1: cookies belong to the URL that actually answered; a different host is no sign-in.
        val answered = response.request.url
        if (answered.host != request.url.host) {
            response.close()
            throw SignInHttpStatusException(response.code)
        }
        val now = System.currentTimeMillis()
        val cookies = Cookie.parseAll(answered, response.headers).filter { it.matches(answered) && it.expiresAt > now }
        val sessionId = cookies.firstOrNull { it.name == "session_id" }
            ?.value?.takeIf { sid -> sid.isNotBlank() && sid.none { it <= ' ' || it == ';' || it >= '\u007f' } }

        val parsed = try {
            gson.fromJson(responseBody, JsonRpcResponse::class.java)
        } catch (e: JsonParseException) {
            null
        } ?: throw InvalidSignInResponseException()
        return SignInResponse(parsed, request.url.host, cookies, sessionId)
    }

    /** Status only; LoginScreen renders the localized `error_server_http` text (iOS parity). */
    private fun signInHttpStatus(code: Int) =
        AuthResult.Error("HTTP $code", AuthResult.ErrorType.SERVER_ERROR, httpStatus = code)

    private class SignInHttpStatusException(val code: Int) : Exception("HTTP $code")
}

/** pi 1001c P1: the server's answer about a session's owner; see [OdooJsonRpcClient.sessionOwnership]. */
sealed interface SessionOwnership {
    /** The server answered: this session is the account's (uid AND db). */
    object Belongs : SessionOwnership
    /** The server answered: another uid/db, or the session is expired. Not the account's — and maybe someone else's. */
    object ProvenMismatch : SessionOwnership
    /** No usable answer (offline, timeout, non-200, unparsable, db missing): proves nothing either way. */
    object Unknown : SessionOwnership
}

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Map<String, Any?>,
    val id: Int
)

data class JsonRpcResponse(
    val jsonrpc: String?,
    val id: Int?,
    val result: JsonObject?,
    val error: JsonRpcError?
)

data class JsonRpcError(
    val code: Int?,
    val message: String?,
    val data: JsonRpcErrorData?
)

data class JsonRpcErrorData(
    val name: String?,
    val message: String?,
    @SerializedName("debug")
    val debug: String?
)
