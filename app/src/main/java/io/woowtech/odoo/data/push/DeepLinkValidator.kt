package io.woowtech.odoo.data.push

import java.net.URI

/**
 * Validates deep link URLs to prevent injection attacks.
 * Only allows relative Odoo paths starting with /web.
 * Rejects javascript:, data:, and external host URLs.
 *
 * Uses java.net.URI instead of android.net.Uri for unit test compatibility.
 */
object DeepLinkValidator {

    /**
     * Anchored match for a safe relative Odoo path: "/web" followed by a path/query/fragment
     * delimiter or end-of-string. Mirrors iOS's `^/web([/?#]|$)`. The remainder after the delimiter
     * is intentionally unconstrained here — traversal is rejected separately by the `..` check.
     */
    private val RELATIVE_WEB_PATH = Regex("^/web([/?#].*)?$")

    /**
     * Validates that an action URL is safe to load in the WebView.
     *
     * @param url the URL from a notification deep link
     * @param serverHost the expected Odoo server hostname
     * @return true if the URL is safe to load
     */
    fun isValid(url: String, serverHost: String): Boolean {
        if (url.isBlank()) return false

        val lower = url.lowercase().trim()

        // Reject dangerous schemes
        if (lower.startsWith("javascript:") || lower.startsWith("data:")) {
            return false
        }

        // Reject path traversal anywhere in the URL (parity with iOS DeepLinkValidator).
        if (url.contains("..")) {
            return false
        }

        // Allow relative Odoo paths, but only when "/web" is a real path segment. Anchored match
        // equivalent to iOS's ^/web([/?#]|$): accepts "/web", "/web/...", "/web?...", "/web#...";
        // rejects "/website/", "/webhook", "/web@evil.com" that merely share the "/web" prefix.
        if (RELATIVE_WEB_PATH.matches(url)) {
            return true
        }

        // For absolute URLs, verify same host
        return try {
            val parsed = URI(url)
            val urlHost = parsed.host ?: return false
            urlHost.equals(serverHost, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}
