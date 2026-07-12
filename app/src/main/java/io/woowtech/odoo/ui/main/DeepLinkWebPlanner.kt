package io.woowtech.odoo.ui.main

import java.net.URI

/**
 * Pure helpers that decide *how* a pending deep link is driven into the single, long-lived
 * WebView. Extracted from [OdooWebView] so the routing rules can be unit-tested without a real
 * WebView (which needs instrumentation).
 *
 * The two shapes of navigation:
 * - **Reload** — used when switching accounts / on a fresh page: load the target server, then let
 *   `onPageFinished` apply the fragment once the host matches.
 * - **FragmentNav** — used for the *warm* case (already on the target host, only the
 *   `#...active_id=mail.channel_N` fragment changes): a plain `loadUrl` is a no-op inside the SPA,
 *   so the hash must be set via JavaScript and a `hashchange` event dispatched.
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
     * Extracts the URL fragment (everything after the first `#`) from a deep link, or null when
     * there is none. Odoo client-action deep links are fragment-based
     * (e.g. `/web#active_id=mail.channel_7`), which is what enables warm hash navigation.
     */
    fun fragmentOf(deepLink: String?): String? {
        if (deepLink.isNullOrBlank()) return null
        val hashIndex = deepLink.indexOf('#')
        if (hashIndex < 0) return null
        val fragment = deepLink.substring(hashIndex + 1)
        return fragment.ifBlank { null }
    }

    /**
     * Builds the JavaScript that performs a warm, in-SPA fragment navigation: it sets
     * `location.hash` (only if it actually changed) and dispatches a `hashchange` event so the
     * Odoo web client reacts, because assigning an unchanged hash fires nothing on its own.
     *
     * [fragment] is embedded as a JSON string literal so quotes / backslashes cannot break out of
     * the script.
     */
    fun buildFragmentNavJs(fragment: String): String {
        val encoded = encodeJsString(fragment)
        return """
            (function() {
                var target = $encoded;
                if (location.hash.replace(/^#/, '') !== target) {
                    location.hash = target;
                }
                window.dispatchEvent(new HashChangeEvent('hashchange'));
            })();
        """.trimIndent()
    }

    /**
     * Minimal JSON string encoder for the small, controlled fragment values that reach the
     * WebView. Escapes the characters that would otherwise let a value break out of the string
     * literal or inject markup.
     */
    private fun encodeJsString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '<' -> sb.append("\\u003C")
                '>' -> sb.append("\\u003E")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
