package io.woowtech.odoo.data.push

import android.content.Intent
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.repository.AccountRepository
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import java.net.URI
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * External `<brand scheme>://open?url=<encoded>` links (owner-approved 2026-09-29, iOS
 * `odooApp.handleIncomingURL` parity, shared by both brands).
 *
 * The `url` parameter is validated against the ACTIVE account's server with the shared
 * [DeepLinkValidator] and queued bound to that account in [DeepLinkManager]; the existing
 * load-gated WebView apply flow then navigates once the account's page (after any App Lock
 * unlock) has finished loading. Never looser than iOS:
 * - absolute URLs must be `https` and on the active account's host;
 * - relative paths only `^/web([/?#]|$)`; control characters and `..` / `%2e%2e` are rejected;
 * - no signed-in account (e.g. after logout) → ignored, the login screen stays;
 * - a missing `url` parameter only opens the app.
 * Notification taps (carrying [NotificationHelper.EXTRA_ACTION_URL]) keep their own push routing.
 */
@Singleton
class ExternalLinkIntake @Inject constructor(
    private val accountRepository: AccountRepository,
    private val deepLinkManager: DeepLinkManager,
) {

    /** Returns true when a link was queued for the active account; false when [intent] is ignored. */
    suspend fun accept(intent: Intent?): Boolean {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return false
        if (intent.hasExtra(NotificationHelper.EXTRA_ACTION_URL)) return false
        val url = linkUrl(intent.dataString, AppBrand.current.scheme) ?: return false

        val active = accountRepository.activeAccount.firstOrNull()
        if (active == null) {
            Timber.w("Ignoring external link — no signed-in account")
            return false
        }
        val serverHost = runCatching { URI(active.fullServerUrl).host }.getOrNull().orEmpty()
        if (!isStrictlyValid(url, serverHost)) {
            Timber.w("Rejected external link")
            return false
        }
        deepLinkManager.setPending(url = url, accountId = active.id)
        Timber.d("External link pending for active account")
        return true
    }

    private fun isStrictlyValid(url: String, serverHost: String): Boolean {
        if (serverHost.isBlank()) return false
        val trimmed = url.trim()
        if (trimmed.any { it.isISOControl() }) return false
        if (trimmed.lowercase().contains("%2e%2e")) return false
        if (!trimmed.startsWith("/") && !trimmed.lowercase().startsWith("https://")) return false
        return DeepLinkValidator.isValid(url = trimmed, serverHost = serverHost)
    }

    companion object {
        private const val HOST = "open"
        private const val PARAM = "url"

        /** First `url` query value of `<scheme>://open?...`, percent-decoded (`+` kept literal, as iOS). */
        internal fun linkUrl(data: String?, scheme: String): String? {
            if (data.isNullOrBlank()) return null
            return runCatching {
                val uri = URI(data)
                if (uri.scheme != scheme || uri.host != HOST) return null
                val raw = uri.rawQuery ?: return null
                raw.split('&')
                    .firstOrNull { it.substringBefore('=') == PARAM && it.contains('=') }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8.name()) }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
