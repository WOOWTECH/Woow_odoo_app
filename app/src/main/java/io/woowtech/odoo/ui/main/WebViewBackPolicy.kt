package io.woowtech.odoo.ui.main

import android.webkit.WebView
import java.net.URI

/**
 * D1 (2026-09-28 Android full run, A06-01..A06-12): system back on the main screen closed the app
 * instead of returning to the previous Odoo page, and the user's place was lost.
 *
 * System back now steps back through the WebView's own history, but only to a page of the active
 * account's host that is not the Odoo login page. Anything else (first page, a leftover page of the
 * previously active account, `/web/login`) is left to NavHost/Activity — i.e. the root still exits.
 *
 * D-R2-2 (2026-09-29 round 2, A06-13..A06-15): the WebView records the first page as two entries —
 * the start URL (`/web?db=` redirected by the server to `/odoo?db=`) and then the entry Odoo's
 * router pushes for its default app (Discuss). Going back to the start URL only replays Inbox, so it
 * took one extra back to leave the app. A start URL at the history root is therefore not a page to
 * go back to. A hash-routed root (`/web#action=…`, Odoo <= 17) is a real page and stays eligible.
 *
 * Pure (`java.net.URI` via [DeepLinkWebPlanner]) so it unit-tests on the plain JVM.
 */
internal object WebViewBackPolicy {
    private val START_PATHS = setOf("/web", "/web/", "/odoo", "/odoo/")

    fun canNavigateBack(canGoBack: Boolean, previousUrl: String?, previousIndex: Int, serverUrl: String): Boolean {
        if (!canGoBack || previousUrl == null) return false
        if (!DeepLinkWebPlanner.hostMatches(loadedUrl = previousUrl, targetServerUrl = serverUrl)) return false
        if (DeepLinkWebPlanner.pathOf(previousUrl)?.startsWith("/web/login") == true) return false
        return !(previousIndex == 0 && isStartUrl(previousUrl))
    }

    /** The bare app entry URL (`/web` or `/odoo`, any query, no fragment) the WebView is started on. */
    private fun isStartUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.path in START_PATHS && uri.rawFragment.isNullOrEmpty()
    }
}

/** URL of the entry one step back in this WebView's history, or null when there is none. */
internal fun WebView.previousHistoryUrl(): String? {
    val history = copyBackForwardList()
    val index = history.currentIndex - 1
    return if (index >= 0) history.getItemAtIndex(index)?.url else null
}

/** History index of the entry one step back in this WebView, or -1 when there is none. */
internal fun WebView.previousHistoryIndex(): Int = copyBackForwardList().currentIndex - 1
