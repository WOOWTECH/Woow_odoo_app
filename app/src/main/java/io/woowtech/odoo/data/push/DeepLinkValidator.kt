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

        // Allow relative paths starting with /web
        if (url.startsWith("/web")) {
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
