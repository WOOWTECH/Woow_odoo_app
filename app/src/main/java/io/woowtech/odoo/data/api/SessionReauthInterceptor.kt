package io.woowtech.odoo.data.api

import com.google.gson.Gson
import io.woowtech.odoo.data.repository.FcmRequestAccount
import io.woowtech.odoo.data.repository.SessionCookieProvider
import com.google.gson.JsonObject
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.net.HttpURLConnection

/**
 * OkHttp [Interceptor] that transparently self-heals an expired Odoo **session cookie** on the FCM
 * register / unregister calls.
 *
 * ## Why an interceptor and not an [okhttp3.Authenticator]
 * `/woow_fcm_push/register` and `/unregister` are `type='json', auth='user'` Odoo routes. For
 * `type='json'` routes Odoo returns an expired session as **HTTP 200 with a JSON-RPC error envelope**:
 * ```
 * {"jsonrpc":"2.0","id":1,"error":{"code":100,"message":"Odoo Session Expired",
 *   "data":{"name":"odoo.http.SessionExpiredException", ...}}}
 * ```
 * Because the transport code is 200, an OkHttp `Authenticator` (which only fires on HTTP 401) never
 * triggers. This interceptor inspects the **response body** instead, so it detects both the real-world
 * 200-error-body case and a genuine transport-level 401.
 *
 * ## Behaviour
 * On a detected session expiry it invokes the guardrail'd [SessionReauthenticator] to refresh the
 * session cookie (all six security guardrails — https-only + exact host, single-flight, cause
 * distinction, circuit breaker, no credential logging — live in that engine), then replays the request
 * exactly **once**. The retry carries [SessionReauthenticator.RETRY_MARKER_HEADER] and the interceptor
 * also refuses to re-enter for an already-marked request, so there is a hard one-retry cap and no loop.
 * The refreshed session cookie is re-resolved per account and set on the retried request — the client has no `CookieJar` (story 8-2, P0-3).
 */
class SessionReauthInterceptor(
    private val reauthenticator: SessionReauthenticator,
    /**
     * Supplies the FRESH per-account cookie for a replayed request. Nullable so callers that never
     * attach an account tag (and therefore never replay a tagged request) need not provide one.
     */
    private val cookieProvider: SessionCookieProvider? = null,
) : Interceptor {

    private val gson = Gson()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // One-retry cap: never attempt re-auth for a request we already retried.
        if (request.header(SessionReauthenticator.RETRY_MARKER_HEADER) != null) {
            return response
        }

        // Peek the (tiny JSON) body without consuming it, so the original response is still readable by
        // the caller when we decide NOT to retry.
        val bodyText = runCatching {
            response.peekBody(MAX_PEEK_BYTES).string()
        }.getOrNull()

        if (!isSessionExpired(httpCode = response.code, body = bodyText)) {
            return response
        }

        // Story 8-2 (P0-3): prefer the account stamped on the request. Resolving by HOST picks the
        // most-recently-used account sharing that host, so with two accounts on one Odoo server it
        // re-authenticated the WRONG one and then replayed the request under that sibling's
        // identity — creating a device row for the wrong user while reporting success for the right
        // one, and writing the response's tenant id onto the wrong account row.
        val tagged = request.tag(FcmRequestAccount::class.java)
        val reauthOk = if (tagged != null) {
            reauthenticator.reauthenticateForAccount(tagged.accountId)
        } else {
            reauthenticator.reauthenticateForHost(request.url.host)
        }
        if (!reauthOk) {
            return response
        }

        // Refresh succeeded — replay the ORIGINAL request once.
        //
        // The Cookie header MUST be re-resolved here. `request.newBuilder()` reuses the original
        // headers, and the client deliberately carries no CookieJar (see buildFcmHttpClient), so a
        // plain replay would re-present the STALE cookie and the re-auth would accomplish nothing.
        response.close()
        val builder = request.newBuilder()
            .header(SessionReauthenticator.RETRY_MARKER_HEADER, "1")
        if (tagged != null) {
            val fresh = cookieProvider?.getCookiesForAccount(tagged.accountId).orEmpty()
            if (fresh.isNotEmpty()) {
                builder.header("Cookie", fresh.joinToString("; ") { "${it.name}=${it.value}" })
            }
        }
        return chain.proceed(builder.build())
    }

    /**
     * Detects an expired Odoo session from a response. True when EITHER the transport [httpCode] is
     * 401 OR the parsed JSON-RPC [body] carries an `error` object identifying a session expiry
     * (`data.name == "odoo.http.SessionExpiredException"`, or the `code == 100` session-expired
     * fallback). Tolerant: a null / non-JSON / success body simply yields false, never a crash.
     */
    private fun isSessionExpired(httpCode: Int, body: String?): Boolean {
        if (httpCode == HttpURLConnection.HTTP_UNAUTHORIZED) return true
        if (body.isNullOrBlank()) return false

        val error = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java) ?: return false
            val errorElement = root.get("error")
            if (errorElement == null || !errorElement.isJsonObject) return false
            errorElement.asJsonObject
        }.getOrNull() ?: return false

        // Preferred signal: the exact exception name in error.data.name.
        val dataName = runCatching {
            error.getAsJsonObject("data")?.get("name")?.asString
        }.getOrNull()
        if (dataName == SESSION_EXPIRED_EXCEPTION) return true

        // Fallback: Odoo session-expired errors carry code == 100 with a session-expired message.
        val code = runCatching { error.get("code")?.asInt }.getOrNull()
        if (code == SESSION_EXPIRED_CODE) {
            val message = runCatching { error.get("message")?.asString }.getOrNull().orEmpty()
            if (message.contains(SESSION_EXPIRED_MESSAGE_HINT, ignoreCase = true)) return true
        }

        return false
    }

    companion object {
        /** Odoo's exception class name for an expired session, carried in `error.data.name`. */
        private const val SESSION_EXPIRED_EXCEPTION = "odoo.http.SessionExpiredException"

        /** Odoo JSON-RPC error code accompanying a session-expired envelope. */
        private const val SESSION_EXPIRED_CODE = 100

        /** Substring identifying a session-expired message on the code==100 fallback path. */
        private const val SESSION_EXPIRED_MESSAGE_HINT = "session expired"

        /**
         * Upper bound on the response body we peek for expiry detection. The register/unregister
         * responses are tiny JSON envelopes; this cap keeps a malformed/huge body from being buffered
         * whole while comfortably covering any real Odoo error payload.
         */
        private const val MAX_PEEK_BYTES = 64L * 1024L
    }
}
