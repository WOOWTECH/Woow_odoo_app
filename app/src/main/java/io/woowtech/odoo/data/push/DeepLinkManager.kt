package io.woowtech.odoo.data.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A deep link waiting to be applied to the WebView, bound to the account that must display it.
 *
 * Binding the link to [accountId] is the core of the cross-tenant isolation guarantee: the link
 * is only ever applied while the account it targets is active, so account A can never open a link
 * that originated from account B (and vice-versa).
 *
 * @property url the validated relative Odoo path / fragment to navigate to (e.g. "/web#id=42&model=...")
 * @property accountId the id of the account this link belongs to
 * @property createdAtMillis wall-clock time the link was queued, used to enforce [DeepLinkManager.TTL_MILLIS]
 */
data class PendingDeepLink(
    val url: String,
    val accountId: String,
    val createdAtMillis: Long,
)

/**
 * Holds at most one pending deep link between a notification tap and the moment the target
 * account's WebView finishes loading. The link is:
 *
 * - **bound to a target account** — [consumeFor] only returns it for the matching account id;
 * - **single-consume** — reading it clears it, so a link is never applied twice;
 * - **time-boxed** — a link older than [TTL_MILLIS] is discarded rather than applied to a stale
 *   session;
 * - **dropped on a foreign login** — [dropIfNotTarget] clears the link if a different account
 *   becomes active before it is consumed.
 *
 * The wall clock is passed in (defaulting to [System.currentTimeMillis]) so TTL behaviour can be
 * exercised deterministically in unit tests.
 */
@Singleton
class DeepLinkManager @Inject constructor() {

    private val _pending = MutableStateFlow<PendingDeepLink?>(null)

    /** Observable pending deep link, or null when none is queued. */
    val pending: StateFlow<PendingDeepLink?> = _pending.asStateFlow()

    /**
     * Queues [url] to be applied once the account identified by [accountId] is active and its page
     * has finished loading. Replaces any previously queued link (the newest tap wins).
     *
     * @param url validated relative path / fragment to navigate to; a null or blank value clears the queue
     * @param accountId the account this link must be shown on
     * @param nowMillis current wall-clock time; injected for testability
     */
    fun setPending(url: String?, accountId: String, nowMillis: Long = System.currentTimeMillis()) {
        _pending.value = if (url.isNullOrBlank()) {
            null
        } else {
            PendingDeepLink(url = url, accountId = accountId, createdAtMillis = nowMillis)
        }
    }

    /**
     * Returns and clears the pending link **only** if it targets [accountId] and has not expired.
     *
     * Returns null (and clears any expired entry) when there is no link, the link targets a
     * different account, or the link is older than [TTL_MILLIS]. This is the single point where a
     * link is handed to the WebView, guaranteeing at-most-once application to the correct account.
     */
    fun consumeFor(accountId: String, nowMillis: Long = System.currentTimeMillis()): String? {
        val current = _pending.value ?: return null

        if (nowMillis - current.createdAtMillis > TTL_MILLIS) {
            _pending.value = null
            return null
        }

        if (current.accountId != accountId) {
            return null
        }

        _pending.value = null
        return current.url
    }

    /**
     * Drops the pending link if it does NOT belong to [activeAccountId]. Call this whenever an
     * account becomes active so a link queued for account B can never survive into account A's
     * session (defence in depth on top of [consumeFor]'s account check).
     */
    fun dropIfNotTarget(activeAccountId: String) {
        val current = _pending.value ?: return
        if (current.accountId != activeAccountId) {
            _pending.value = null
        }
    }

    /** Clears any pending link unconditionally. */
    fun clear() {
        _pending.value = null
    }

    companion object {
        /**
         * Maximum age of a pending deep link. A tap that is not consumed within this window
         * (e.g. the app was never brought forward) is discarded rather than applied to a session
         * that may have changed in the meantime. Five minutes comfortably covers the auth flow.
         */
        const val TTL_MILLIS = 5 * 60 * 1000L
    }
}
