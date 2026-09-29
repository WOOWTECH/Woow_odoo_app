package io.woowtech.odoo.ui.main

import android.webkit.CookieManager

/**
 * The cookie operations the account-switch sequence needs from the process-global WebView
 * [CookieManager]. An interface so the ordering (clear → install → flush → load) can be driven and
 * verified in tests with a store whose asynchronous removal completes on demand.
 */
interface WebViewCookieStore {
    fun getCookie(url: String): String?

    /** Removes every cookie; [onDone] runs once Chromium has actually finished removing them. */
    fun removeAllCookies(onDone: () -> Unit)

    fun setCookie(url: String, value: String)

    fun flush()
}

/** The real store: Android's [CookieManager]. Its removal callback runs on the calling (UI) thread. */
object AndroidWebViewCookieStore : WebViewCookieStore {
    override fun getCookie(url: String): String? = CookieManager.getInstance().getCookie(url)

    override fun removeAllCookies(onDone: () -> Unit) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.removeAllCookies { onDone() }
    }

    override fun setCookie(url: String, value: String) {
        CookieManager.getInstance().setCookie(url, value)
    }

    override fun flush() {
        CookieManager.getInstance().flush()
    }
}

/**
 * Enforces per-account cookie isolation: clears every cookie and, only once Chromium reports the
 * removal finished, installs [sessionId] for [serverUrl] (if any), flushes, and then runs [then]
 * (the target account's page load). Loading before the removal completes could present the previous
 * account's still-present cookies to the new account's page (pi 0929 recheck).
 *
 * The cookie store is process-global, so every side effect after the removal re-checks [isCurrent]:
 * once a later account switch superseded this one, its continuation writes, flushes and loads nothing
 * (pi 0929 recheck-2). [onSettled] runs when the removal callback finished, current or not, so a
 * [WebViewCookieSequencer] can start the next job.
 */
fun isolateCookiesForAccount(
    store: WebViewCookieStore,
    serverUrl: String,
    sessionId: String?,
    isCurrent: () -> Boolean = { true },
    onSettled: () -> Unit = {},
    then: () -> Unit,
) {
    store.removeAllCookies {
        try {
            if (!isCurrent()) return@removeAllCookies
            if (sessionId != null) {
                // Max-Age (Odoo's default 7-day session lifetime) makes the cookie persistent, so a cold
                // start still finds it; the server stays the judge of whether the session is valid.
                store.setCookie(serverUrl, "session_id=$sessionId; Path=/; Secure; Max-Age=$WEBVIEW_SESSION_COOKIE_MAX_AGE_SECONDS")
            }
            if (!isCurrent()) return@removeAllCookies
            store.flush()
            if (!isCurrent()) return@removeAllCookies
            then()
        } finally {
            onSettled()
        }
    }
}

/**
 * Runs cookie jobs against the process-global cookie store strictly one at a time (pi 0929 recheck-2).
 *
 * Chromium removes cookies asynchronously. Without serialization a fast A→B→C switch issues two
 * removals whose callbacks may arrive in either order, letting B's stale continuation write B's
 * session after C's. Here C's job only starts once B's has settled, so callbacks can never
 * interleave; a queued job whose switch was superseded before it started is dropped entirely.
 * Main thread only (Compose and the CookieManager removal callback both run there).
 */
class WebViewCookieSequencer {
    private class Job(val isCurrent: () -> Boolean, val run: (done: () -> Unit) -> Unit)

    private val queue = ArrayDeque<Job>()
    private var busy = false

    /** Queues [run]; it must call `done` exactly once when its cookie work settled. */
    fun enqueue(isCurrent: () -> Boolean, run: (done: () -> Unit) -> Unit) {
        queue.addLast(Job(isCurrent, run))
        if (!busy) startNext()
    }

    private fun startNext() {
        while (true) {
            val job = queue.removeFirstOrNull() ?: return
            if (!job.isCurrent()) continue
            busy = true
            var settled = false
            job.run {
                if (!settled) {
                    settled = true
                    busy = false
                    startNext()
                }
            }
            return
        }
    }
}

/** Odoo's default session lifetime (`SESSION_LIFETIME`, 7 days). */
private const val WEBVIEW_SESSION_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60

/**
 * Decides whether a finished page belongs to the account the single WebView is switching to
 * (pi 0929 recheck P1).
 *
 * After an account switch — including two accounts on the same host, where a host comparison cannot
 * tell them apart — the previous account's page may still deliver a late `onPageFinished`. Such an
 * event must not open the "target page loaded" gate: it would apply the new account's pending deep
 * link, clear the history and mark the page loaded before the new account's page exists.
 *
 * Each switch starts a new generation. A finished page only counts for that generation once the
 * switch's own load was issued ([onLoadIssued]) and a navigation start was seen after it
 * ([onPageStarted]; Chromium reports the start when the new document commits, which is after the
 * previous account's navigation was cancelled). Before any switch (cold start) and after the switch
 * page was accepted, the rule is unchanged: a finished page counts when it is on the target host.
 */
class WebViewSwitchLoadGate {
    private var generation = 0
    private var awaitingGeneration: Int? = null
    private var loadIssued = false
    private var started = false

    /** A new account switch begins; returns its generation for [isCurrent] / [onLoadIssued]. */
    fun beginSwitch(): Int {
        generation += 1
        awaitingGeneration = generation
        loadIssued = false
        started = false
        return generation
    }

    /** False once a later switch superseded [switchGeneration] (its deferred load must not run). */
    fun isCurrent(switchGeneration: Int): Boolean = switchGeneration == generation

    /** The switch's target page load for [switchGeneration] was just handed to the WebView. */
    fun onLoadIssued(switchGeneration: Int) {
        if (switchGeneration == generation && awaitingGeneration == switchGeneration) {
            loadIssued = true
            started = false
        }
    }

    fun onPageStarted() {
        if (awaitingGeneration != null && loadIssued) started = true
    }

    /**
     * @param onTargetHost whether the finished URL is on the target account's server host.
     * @return whether the finished page counts as the target account's loaded page.
     */
    fun acceptFinished(onTargetHost: Boolean): Boolean {
        if (awaitingGeneration == null) return onTargetHost
        if (!loadIssued || !started || !onTargetHost) return false
        awaitingGeneration = null
        return true
    }
}
