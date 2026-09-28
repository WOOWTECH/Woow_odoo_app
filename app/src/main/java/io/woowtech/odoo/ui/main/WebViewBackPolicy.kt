package io.woowtech.odoo.ui.main

import android.webkit.WebView

/**
 * D1 (2026-09-28 Android full run, A06-01..A06-12): system back on the main screen closed the app
 * instead of returning to the previous Odoo page, and the user's place was lost.
 *
 * System back now steps back through the WebView's own history, but only to a page of the active
 * account's host that is not the Odoo login page. Anything else (first page, a leftover page of the
 * previously active account, `/web/login`) is left to NavHost/Activity — i.e. the root still exits.
 *
 * Pure (`java.net.URI` via [DeepLinkWebPlanner]) so it unit-tests on the plain JVM.
 */
internal object WebViewBackPolicy {
    fun canNavigateBack(canGoBack: Boolean, previousUrl: String?, serverUrl: String): Boolean {
        if (!canGoBack || previousUrl == null) return false
        if (!DeepLinkWebPlanner.hostMatches(loadedUrl = previousUrl, targetServerUrl = serverUrl)) return false
        return DeepLinkWebPlanner.pathOf(previousUrl)?.startsWith("/web/login") != true
    }
}

/** URL of the entry one step back in this WebView's history, or null when there is none. */
internal fun WebView.previousHistoryUrl(): String? {
    val history = copyBackForwardList()
    val index = history.currentIndex - 1
    return if (index >= 0) history.getItemAtIndex(index)?.url else null
}
