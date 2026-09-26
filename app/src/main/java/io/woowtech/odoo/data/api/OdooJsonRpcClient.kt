package io.woowtech.odoo.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
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
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                clear()
                addAll(cookies)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client: OkHttpClient = (sharedAuthClient?.newBuilder() ?: OkHttpClient.Builder())
        .cookieJar(cookieJar)
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

            val response = executeRequest(url, requestBody)

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
            val sessionId = getSessionId(extractHost(serverUrl)) ?: ""
            val name = result.get("name")?.asString ?: username

            AuthResult.Success(
                userId = uid,
                sessionId = sessionId,
                username = username,
                displayName = name
            )
        } catch (e: SignInHttpStatusException) {
            signInHttpStatus(e.code)
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
                val error = envelope?.getAsJsonObject("error")
                if (error != null) {
                    val denied = error.getAsJsonObject("data")?.get("name")?.asString == "odoo.exceptions.AccessDenied"
                    return AuthResult.Error("Sign-in request rejected", if (denied) AuthResult.ErrorType.INVALID_CREDENTIALS else AuthResult.ErrorType.SERVER_ERROR)
                }
                val result = envelope?.getAsJsonObject("result")
                val uid = runCatching { result?.get("uid")?.asInt }.getOrNull()
                if (uid == null || uid <= 0) return AuthResult.Error("Invalid credentials", AuthResult.ErrorType.INVALID_CREDENTIALS)
                val sid = Cookie.parseAll(request.url, response.headers)
                    .firstOrNull { it.name == "session_id" && it.matches(request.url) && it.expiresAt > System.currentTimeMillis() }
                    ?.value.orEmpty()
                if (sid.isBlank() || sid.any { it <= ' ' || it == ';' || it >= '\u007f' }) return AuthResult.Error("Sign-in session was not established", AuthResult.ErrorType.SESSION_EXPIRED)
                AuthResult.Success(uid, sid, username, result?.get("name")?.asString ?: username)
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
        val url = serverUrl.toHttpUrlOrNull() ?: return
        require(sessionId.isNotBlank()) { "A proven session is required" }
        val cookie = Cookie.Builder().name("session_id").value(sessionId)
            .hostOnlyDomain(url.host).path("/").secure().httpOnly().build()
        cookieStore[url.host] = mutableListOf(cookie)
    }

    private fun executeRequest(url: String, body: JsonRpcRequest): JsonRpcResponse {
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

        return gson.fromJson(responseBody, JsonRpcResponse::class.java)
    }

    /** Status only; LoginScreen renders the localized `error_server_http` text (iOS parity). */
    private fun signInHttpStatus(code: Int) =
        AuthResult.Error("HTTP $code", AuthResult.ErrorType.SERVER_ERROR, httpStatus = code)

    private class SignInHttpStatusException(val code: Int) : Exception("HTTP $code")

    private fun extractHost(url: String): String {
        return url.removePrefix("https://").removePrefix("http://").split("/").first()
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
