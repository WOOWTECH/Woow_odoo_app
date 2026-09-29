package io.woowtech.odoo.ui.main

import android.webkit.WebStorage
import io.woowtech.odoo.data.local.WebViewCookieOwnerStore
import io.woowtech.odoo.data.push.DeepLinkManager
import io.woowtech.odoo.data.repository.AccountWebDataCleaner
import io.woowtech.odoo.data.repository.CacheRepository
import io.woowtech.odoo.data.repository.WebDataRemoval
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The WebView side of a logout / account removal (iOS D1 parity, 2026-09-30).
 *
 * Cookie removal goes through the process-wide [WebViewCookieCoordinator]: it takes a new target token
 * (so the removed account's still-displayed composition can no longer install or load anything) and
 * queues the removal on the shared sequencer. If another account's composition takes a newer token
 * before the removal starts, the queued removal is dropped — that account's own cookie job clears every
 * cookie first anyway, and the owner record was already cleared here, so it can never keep the removed
 * account's cookies.
 */
@Singleton
class AndroidAccountWebDataCleaner internal constructor(
    private val ownerStore: WebViewCookieOwnerStore,
    private val deepLinkManager: DeepLinkManager,
    private val cookieStore: WebViewCookieStore,
    private val coordinator: WebViewCookieCoordinator,
    private val deleteOriginStorage: (String) -> Unit,
    private val clearEverything: suspend () -> Unit,
    private val mainDispatcher: CoroutineDispatcher,
) : AccountWebDataCleaner {

    @Inject
    constructor(
        ownerStore: WebViewCookieOwnerStore,
        deepLinkManager: DeepLinkManager,
        cacheRepository: CacheRepository,
    ) : this(
        ownerStore,
        deepLinkManager,
        AndroidWebViewCookieStore,
        WebViewCookieCoordinator.Process,
        deleteOriginStorage = { origin -> WebStorage.getInstance().deleteOrigin(origin) },
        clearEverything = { cacheRepository.clearWebViewCache() },
        mainDispatcher = Dispatchers.Main,
    )

    override suspend fun webViewSessionIdOf(accountId: String, serverUrl: String): String? =
        withContext(mainDispatcher) {
            if (ownerStore.ownerAccountId() != accountId) return@withContext null
            sessionIdFrom(runCatching { cookieStore.getCookie(serverUrl) }.getOrNull())
        }

    override suspend fun removeAccountData(accountId: String, serverUrl: String, removal: WebDataRemoval): Unit =
        withContext(mainDispatcher) {
            deepLinkManager.dropFor(accountId)
            if (removal.cookies || removal.everything) {
                ownerStore.recordCleared()
                val token = coordinator.beginTarget()
                coordinator.sequencer.enqueue({ coordinator.isCurrent(token) }) { done ->
                    cookieStore.removeAllCookies {
                        try {
                            if (coordinator.isCurrent(token)) cookieStore.flush()
                        } finally {
                            done()
                        }
                    }
                }
            }
            if (removal.everything) {
                runCatching { clearEverything() }.onFailure { Timber.w(it, "WebView data not cleared after last logout") }
            } else if (removal.originStorage) {
                storageOriginOf(serverUrl)?.let { origin ->
                    runCatching { deleteOriginStorage(origin) }.onFailure { Timber.w(it, "WebView site storage not cleared") }
                }
            }
            Unit
        }

    internal companion object {
        /** The `session_id` value in a `CookieManager.getCookie(url)` header, if any. */
        fun sessionIdFrom(cookieHeader: String?): String? =
            cookieHeader.orEmpty().split(';').firstNotNullOfOrNull { pair ->
                val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
                val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
                value.takeIf { name == "session_id" && it.isNotEmpty() }
            }

        /** The origin string WebStorage uses: scheme://host, plus :port only when it is not the default. */
        fun storageOriginOf(serverUrl: String): String? = serverUrl.toHttpUrlOrNull()?.let { url ->
            val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            "${url.scheme}://${url.host}$port"
        }
    }
}
