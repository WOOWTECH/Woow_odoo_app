package io.woowtech.odoo.ui.main

import io.woowtech.odoo.data.push.DeepLinkValidator
import java.net.URI

/**
 * Pure helpers that decide *how* a pending deep link is driven into the single, long-lived
 * WebView. Extracted from [OdooWebView] so the routing rules can be unit-tested without a real
 * WebView (which needs instrumentation).
 *
 * Navigation is always a **full cross-document load** of the account's `serverUrl` plus the
 * relative deep link (preserving any `#fragment`). There is no in-SPA hash-poke strategy: Odoo 18's
 * path router (`/odoo/...`) ignores `location.hash`/`hashchange`, and Odoo migrates the legacy hash
 * (e.g. `/web#action=…&active_id=discuss.channel_N`) to the correct `/odoo/...` route at boot. A
 * full load of `/web#…` is therefore correct everywhere; the only cost is a page reload in the rare
 * warm same-thread case, which the app already does on account switches.
 */
object DeepLinkWebPlanner {

    /** Returns the lower-cased host of [url], or null if it cannot be parsed. */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val raw = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                // Relative path (e.g. "/web#..."): no host of its own.
                return null
            }
            URI(raw).host?.lowercase()
        }.getOrNull()
    }

    /**
     * Returns true when [loadedUrl] was served by the same host as [targetServerUrl]. This is the
     * load-gate: the pending deep link is only applied once a page from the *target* account's
     * server has finished loading — never a page still on the previous account's host.
     */
    fun hostMatches(loadedUrl: String?, targetServerUrl: String): Boolean {
        val targetHost = hostOf(targetServerUrl) ?: return false
        val loadedHost = hostOf(loadedUrl) ?: return false
        return loadedHost == targetHost
    }

    /**
     * Returns the path component of [url] (e.g. "/web", "/odoo"). Absolute URLs are parsed; a
     * relative deep link returns everything before its `#`/`?`. Null when blank/unparseable.
     */
    fun pathOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                URI(url).path
            } else {
                url.substringBefore('#').substringBefore('?')
            }
        }.getOrNull()?.ifBlank { "/" }
    }

    /**
     * Builds the absolute URL for a full-page deep-link navigation: the account's server plus the
     * validated relative deep link, preserving its `#fragment`.
     *
     * Used for the Odoo ≥17/18 path-router case where a `location.hash` poke is ignored: a full load
     * of `/web#…` redirects to `/odoo` (the URL fragment is preserved across the redirect), and Odoo
     * parses+migrates the legacy `#action=…&active_id=discuss.channel_<id>` hash to the correct path
     * route at boot. [serverUrl] is the account's own validated server and [deepLink] has already
     * passed `DeepLinkValidator` (begins with `/web`), so the concatenation is safe.
     */
    fun fullLoadUrl(serverUrl: String, deepLink: String): String {
        return serverUrl.trimEnd('/') + deepLink
    }

    /** How a pending deep link should be driven into the WebView currently showing a page. */
    sealed interface NavPlan {
        /** Full cross-document navigation to this URL (the only navigation shape). */
        data class FullLoad(val url: String) : NavPlan
    }

    /**
     * Decides how to apply [deepLink] to a WebView currently on [currentUrl], for account server
     * [serverUrl]. Always a [NavPlan.FullLoad] of `serverUrl` + the relative deep link (fragment
     * preserved): Odoo 18's path router (`/odoo/...`) ignores `location.hash`, and Odoo migrates the
     * legacy hash to the correct `/odoo/...` route at boot, so a full cross-document load is correct
     * everywhere. Returns null when [deepLink] fails [DeepLinkValidator] against [serverUrl]'s host —
     * the WebView-apply layer re-validates rather than blindly trusting its caller (parity with iOS
     * `deepLinkApplyPlan`), so a caller-supplied `javascript:`/`data:`/traversal/foreign-host URL is
     * a safe no-op instead of being concatenated onto the server URL.
     *
     * [currentUrl] is unused for the routing decision (kept for call-site symmetry and future use).
     * Pure and unit-tested so the routing decision is verifiable without a real WebView.
     */
    fun plan(currentUrl: String?, serverUrl: String, deepLink: String): NavPlan? {
        // Re-validate at the apply layer: do not trust the caller. The host is derived from the
        // account's own serverUrl so only same-host (or safe relative /web) links are accepted.
        val serverHost = hostOf(serverUrl) ?: return null
        if (!DeepLinkValidator.isValid(url = deepLink, serverHost = serverHost)) {
            return null
        }
        return NavPlan.FullLoad(fullLoadUrl(serverUrl, deepLink))
    }
}
