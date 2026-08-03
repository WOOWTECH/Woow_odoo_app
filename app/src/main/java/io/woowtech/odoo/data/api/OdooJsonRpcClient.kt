package io.woowtech.odoo.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import io.woowtech.odoo.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OdooJsonRpcClient(private val httpClient: OkHttpClient? = null) {

    /**
     * Production entry point. The optional client on the primary constructor exists so unit tests
     * can drive real cookie storage without TLS — this class enforces `https://`, and MockWebServer
     * serves plaintext by default. Same seam, same reason, as `FcmTokenRepositoryImpl`.
     */
    @Inject
    constructor() : this(httpClient = null)

    private val gson = Gson()

    /**
     * Session cookies, keyed by **account id** — story 8-2 (P0-3).
     *
     * This map used to be keyed by HOST, with a `CookieJar` whose `saveFromResponse` called
     * `clear()` before writing. That meant authenticating account B on a host did not shadow
     * account A's session, it **deleted** it: the app could hold exactly ONE Odoo session per host,
     * process-wide. Multi-account on one server was therefore impossible by construction, and the
     * FCM registration fan-out silently POSTed every account under whichever session survived.
     *
     * There is deliberately **no `CookieJar`** any more. `authenticate` is the only method that
     * issues a request here, so the cookies are read straight off its response and stored under the
     * account they belong to. A jar cannot do this correctly: `CookieJar.loadForRequest(url)`
     * receives only the URL and can never know which account a request is for.
     */
    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
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

    /** Session cookies for [accountId], or empty when that account has no live session. */
    fun getSessionCookies(accountId: String): List<Cookie> = cookieStore[accountId] ?: emptyList()

    /** The `session_id` value for [accountId], or null when that account has no live session. */
    fun getSessionId(accountId: String): String? =
        cookieStore[accountId]?.find { it.name == "session_id" }?.value

    /** Drops [accountId]'s session. Siblings on the same host are untouched — that is the point. */
    fun clearCookies(accountId: String) {
        cookieStore.remove(accountId)
    }

    /**
     * Authenticates [accountId] and stores its session cookies under that id.
     *
     * [accountId] is required precisely because a host is not an identity: two accounts on one Odoo
     * server each need their own session, and keying on the host made the second login destroy the
     * first (story 8-2, P0-3).
     */
    suspend fun authenticate(
        accountId: String,
        serverUrl: String,
        database: String,
        username: String,
        password: String
    ): AuthResult = withContext(Dispatchers.IO) {
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

            val response = executeRequest(url, requestBody, accountId)

            if (response.error != null) {
                val errorMessage = response.error.data?.message
                    ?: response.error.message
                    ?: "Authentication failed"

                return@withContext when {
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
            if (result == null || !result.has("uid") || result.get("uid").isJsonNull) {
                return@withContext AuthResult.Error(
                    "Invalid credentials",
                    AuthResult.ErrorType.INVALID_CREDENTIALS
                )
            }

            val uid = result.get("uid").asInt
            val sessionId = getSessionId(accountId) ?: ""
            val name = result.get("name")?.asString ?: username

            AuthResult.Success(
                userId = uid,
                sessionId = sessionId,
                username = username,
                displayName = name
            )
        } catch (e: UnknownHostException) {
            AuthResult.Error("Unable to connect to server", AuthResult.ErrorType.NETWORK_ERROR)
        } catch (e: SocketTimeoutException) {
            AuthResult.Error("Connection timeout", AuthResult.ErrorType.NETWORK_ERROR)
        } catch (e: IOException) {
            AuthResult.Error("Network error: ${e.message}", AuthResult.ErrorType.NETWORK_ERROR)
        } catch (e: Exception) {
            AuthResult.Error("Error: ${e.message}", AuthResult.ErrorType.UNKNOWN)
        }
    }

    private fun executeRequest(url: String, body: JsonRpcRequest, accountId: String): JsonRpcResponse {
        val jsonBody = gson.toJson(body)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        // Store this account's cookies explicitly. Replacing rather than merging matches the old
        // jar's behaviour for a SINGLE account (a fresh login supersedes that account's session);
        // what changes is that it can no longer touch a sibling account's entry.
        val cookies = Cookie.parseAll(request.url, response.headers)
        if (cookies.isNotEmpty()) {
            cookieStore[accountId] = cookies
        }
        val responseBody = response.body?.string() ?: throw IOException("Empty response")

        return gson.fromJson(responseBody, JsonRpcResponse::class.java)
    }

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
