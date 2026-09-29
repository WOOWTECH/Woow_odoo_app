package io.woowtech.odoo.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What a logout / account removal must wipe from the WebView (iOS D1 parity, 2026-09-30).
 *
 * @property cookies clear the process-global WebView cookies: the removed account is the displayed
 *   one, so the cookies there are its session. When another account is displayed its own cookie job
 *   already replaced them, and touching them would strand that account.
 * @property originStorage delete the site storage (localStorage / IndexedDB) of the removed account's
 *   origin. Android keeps site storage per ORIGIN, not per account: while another account still uses
 *   the same origin this stays false (a documented limitation, see [AccountWebDataCleaner]).
 * @property everything no account remains: clear all cookies, all site storage and the HTTP cache.
 */
data class WebDataRemoval(
    val cookies: Boolean,
    val originStorage: Boolean,
    val everything: Boolean,
)

/**
 * The WebView side of an account's logout / removal. An interface so the repository's ordering can be
 * verified in JVM tests; the Android implementation lives next to the WebView code.
 *
 * Limitation (Android WebView): cookies are process-global and site storage is per origin, so two
 * accounts on the same origin share site storage while both exist; it is deleted once the last of them
 * is removed.
 */
interface AccountWebDataCleaner {
    /** The WebView's current `session_id` for [serverUrl] when the WebView cookies belong to [accountId]; else null. */
    suspend fun webViewSessionIdOf(accountId: String, serverUrl: String): String?

    /** Removes [accountId]'s local web traces as described by [removal]; also drops its pending deep link. */
    suspend fun removeAccountData(accountId: String, serverUrl: String, removal: WebDataRemoval)
}

/** The cookie-jar key of [serverUrl] (`HttpUrl.host`, no port), or null when it does not parse. */
internal fun sessionHostOf(serverUrl: String): String? = serverUrl.toHttpUrlOrNull()?.host

/** scheme://host:port of [serverUrl] — the unit WebView site storage is kept per. */
internal fun webOriginOf(serverUrl: String): String? =
    serverUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }

/** Starts [block] in [scope] without waiting for it (best-effort server calls such as a session revoke). */
internal fun launchDetached(scope: CoroutineScope, block: suspend () -> Unit) {
    scope.launch { runCatching { block() } }
}
