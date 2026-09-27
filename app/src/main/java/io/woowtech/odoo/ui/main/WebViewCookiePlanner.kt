package io.woowtech.odoo.ui.main

/**
 * What to do with the process-global WebView `CookieManager` before an account's page loads.
 *
 * - [KeepExisting]: the WebView already holds this account's own Odoo session (it survives process
 *   death in Chromium's cookie store, including any rotation Odoo did since login) — leave it alone.
 * - [Replace]: clear every cookie and install [Replace.sessionId], a session the native layer minted
 *   in this process (manual login, account switch or self-heal re-auth).
 * - [Clear]: clear every cookie and install nothing; Odoo will redirect to `/web/login` and the
 *   self-heal / re-login path takes over.
 */
sealed interface WebViewCookiePlan {
    data object KeepExisting : WebViewCookiePlan
    data class Replace(val sessionId: String) : WebViewCookiePlan
    data object Clear : WebViewCookiePlan
}

/**
 * Pure decision behind the WebView cookie isolation (LIVE-0927 Android r2, evidence 59/60).
 *
 * Bug it replaces: every WebView creation cleared ALL cookies and re-installed only the session id
 * found in `OdooJsonRpcClient`'s cookie jar. That jar is in-memory, so after the app process was
 * restarted it was empty and the WebView's still-valid Odoo session was thrown away. With "Remember
 * me" on this cost a silent re-login on every cold start; with it off (W1-10, no stored password)
 * self-heal failed and the user was stranded.
 *
 * Isolation is kept: a WebView cookie is only kept when [WebViewCookieOwnerStore] says it was
 * installed for this very account, so another account's session (same or different host) is never
 * presented after a logout / switch.
 */
object WebViewCookiePlanner {

    /**
     * @param accountId the account about to be shown.
     * @param cookieOwnerAccountId the account the WebView cookies were last installed for (persisted).
     * @param webViewHasSessionCookie whether the WebView currently holds a `session_id` for the
     *   account's server URL.
     * @param nativeSessionId the session id held by the native cookie jar for the account's host
     *   (in-memory; null after a process restart).
     * @param lastInjectedSessionId the native session id this process last installed into the WebView.
     */
    fun plan(
        accountId: String,
        cookieOwnerAccountId: String?,
        webViewHasSessionCookie: Boolean,
        nativeSessionId: String?,
        lastInjectedSessionId: String?,
    ): WebViewCookiePlan {
        val native = nativeSessionId?.takeIf { it.isNotBlank() }
        // A session minted natively since the last install (login, switch, re-auth) always wins:
        // the WebView's own cookie may be the expired one that led to that re-login.
        if (native != null && native != lastInjectedSessionId) return WebViewCookiePlan.Replace(native)
        if (cookieOwnerAccountId == accountId && webViewHasSessionCookie) return WebViewCookiePlan.KeepExisting
        if (native != null) return WebViewCookiePlan.Replace(native)
        return WebViewCookiePlan.Clear
    }

    /** True when a `CookieManager.getCookie(url)` header carries a non-empty `session_id`. */
    fun hasSessionCookie(cookieHeader: String?): Boolean =
        cookieHeader.orEmpty().split(';').any { pair ->
            val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
            val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
            name == SESSION_COOKIE && value.isNotEmpty()
        }

    private const val SESSION_COOKIE = "session_id"
}
