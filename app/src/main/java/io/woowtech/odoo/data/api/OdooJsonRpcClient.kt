package io.woowtech.odoo.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import io.woowtech.odoo.domain.model.AuthResult
import io.woowtech.odoo.domain.model.OdooLanguage
import io.woowtech.odoo.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OdooJsonRpcClient @Inject constructor() {

    private val gson = Gson()
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
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

    suspend fun getUserProfile(
        serverUrl: String,
        database: String,
        userId: Int,
        password: String
    ): UserProfile? = withContext(Dispatchers.IO) {
        try {
            val url = "$serverUrl/jsonrpc"
            // v1.0.18: Only request fields that exist on res.users
            // Removed notification_type as it may not exist on all Odoo versions
            val requestBody = JsonRpcRequest(
                jsonrpc = "2.0",
                method = "call",
                params = mapOf(
                    "service" to "object",
                    "method" to "execute_kw",
                    "args" to listOf(
                        database,
                        userId,
                        password,
                        "res.users",
                        "read",
                        listOf(listOf(userId)),
                        mapOf(
                            "fields" to listOf(
                                "name", "login", "email", "phone",
                                "mobile", "website", "function", "image_1920",
                                // v1.0.18: Safe Odoo preferences (these exist on res.users)
                                "lang", "tz", "signature"
                            )
                        )
                    )
                ),
                id = 2
            )

            val response = executeRequest(url, requestBody)

            // Check for API error
            if (response.error != null) {
                android.util.Log.e("OdooJsonRpcClient", "getUserProfile API error: ${response.error.message}")
                return@withContext null
            }

            val result = response.result?.asJsonArray?.firstOrNull()?.asJsonObject
            if (result == null) {
                android.util.Log.e("OdooJsonRpcClient", "getUserProfile: empty result")
                return@withContext null
            }

            UserProfile(
                id = userId,
                name = result.getStringOrNull("name") ?: "",
                login = result.getStringOrNull("login") ?: "",
                email = result.getStringOrNull("email"),
                phone = result.getStringOrNull("phone"),
                mobile = result.getStringOrNull("mobile"),
                website = result.getStringOrNull("website"),
                function = result.getStringOrNull("function"),
                imageBase64 = result.getStringOrNull("image_1920"),
                // v1.0.18: Safe Odoo preferences
                lang = result.getStringOrNull("lang"),
                tz = result.getStringOrNull("tz"),
                notificationType = "email", // Default value, will fetch from res.partner if needed
                signature = result.getStringOrNull("signature")
            )
        } catch (e: Exception) {
            android.util.Log.e("OdooJsonRpcClient", "getUserProfile error: ${e.message}", e)
            null
        }
    }

    /**
     * v1.0.18: Safe string extraction from JsonObject
     * Handles Odoo's quirk of returning `false` for empty/null fields
     */
    private fun JsonObject.getStringOrNull(key: String): String? {
        val element = this.get(key) ?: return null
        return when {
            element.isJsonNull -> null
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> null // Odoo returns false for empty fields
                    primitive.isString -> primitive.asString.takeIf { it.isNotBlank() }
                    else -> primitive.asString
                }
            }
            element.isJsonArray && element.asJsonArray.size() > 0 -> {
                // Handle Many2one fields that return [id, name] or just the first element
                val first = element.asJsonArray[0]
                if (first.isJsonPrimitive) first.asString else null
            }
            else -> null
        }
    }

    /**
     * v1.0.16: Get available languages from Odoo
     */
    suspend fun getAvailableLanguages(
        serverUrl: String,
        database: String,
        userId: Int,
        password: String
    ): List<OdooLanguage> = withContext(Dispatchers.IO) {
        try {
            val url = "$serverUrl/jsonrpc"
            val requestBody = JsonRpcRequest(
                jsonrpc = "2.0",
                method = "call",
                params = mapOf(
                    "service" to "object",
                    "method" to "execute_kw",
                    "args" to listOf(
                        database,
                        userId,
                        password,
                        "res.lang",
                        "search_read",
                        listOf(listOf(listOf("active", "=", true))),
                        mapOf(
                            "fields" to listOf("code", "name")
                        )
                    )
                ),
                id = 4
            )

            val response = executeRequest(url, requestBody)
            val result = response.result?.asJsonArray ?: return@withContext emptyList()

            result.mapNotNull { item ->
                val obj = item.asJsonObject
                val code = obj.get("code")?.takeIf { !it.isJsonNull }?.asString
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                if (code != null && name != null) {
                    OdooLanguage(code = code, name = name)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OdooJsonRpcClient", "getAvailableLanguages error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun updateUserProfile(
        serverUrl: String,
        database: String,
        userId: Int,
        password: String,
        updates: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$serverUrl/jsonrpc"
            val requestBody = JsonRpcRequest(
                jsonrpc = "2.0",
                method = "call",
                params = mapOf(
                    "service" to "object",
                    "method" to "execute_kw",
                    "args" to listOf(
                        database,
                        userId,
                        password,
                        "res.users",
                        "write",
                        listOf(listOf(userId), updates)
                    )
                ),
                id = 3
            )

            val response = executeRequest(url, requestBody)
            response.error == null
        } catch (e: Exception) {
            android.util.Log.e("OdooJsonRpcClient", "updateUserProfile error: ${e.message}", e)
            false
        }
    }

    private fun executeRequest(url: String, body: JsonRpcRequest): JsonRpcResponse {
        val jsonBody = gson.toJson(body)
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")

        return gson.fromJson(responseBody, JsonRpcResponse::class.java)
    }

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
