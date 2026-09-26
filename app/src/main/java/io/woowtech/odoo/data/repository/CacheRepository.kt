package io.woowtech.odoo.data.repository

import android.content.Context
import android.webkit.WebStorage
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles cache clearing operations without holding a reference to Activity context.
 *
 * "Clear cache" (W1-10) clears the WebView HTTP cache (memory + disk, process-wide) and the
 * WebView site storage (localStorage / IndexedDB / Web SQL). It deliberately does NOT touch
 * cookies (the Odoo session), accounts, stored credentials or app settings — clearing the cache
 * must never sign the user out.
 */
@Singleton
class CacheRepository internal constructor(
    private val context: Context,
    private val clearWebViewHttpCache: () -> Unit,
    private val clearWebSiteStorage: () -> Unit,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        clearWebViewHttpCache = {
            // WebView.clearCache is per-application: one throwaway instance on the application
            // context clears the HTTP cache used by every WebView, and holds no Activity.
            val webView = WebView(context)
            try {
                webView.clearCache(true)
            } finally {
                webView.destroy()
            }
        },
        clearWebSiteStorage = { WebStorage.getInstance().deleteAllData() },
    )

    /**
     * Clears the app's cache directory and returns the new cache size.
     */
    suspend fun clearAppCache(): Long = withContext(Dispatchers.IO) {
        try {
            context.cacheDir.deleteRecursively()
            Timber.d("App cache cleared")
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to clear app cache")
        }
        calculateCacheSize()
    }

    /**
     * Clears the WebView HTTP cache and site storage (never cookies). Runs on the main thread, as
     * WebView requires. Each step is independent: a missing/updating WebView provider must not
     * stop the other step or crash Settings.
     */
    suspend fun clearWebViewCache() = withContext(Dispatchers.Main) {
        try {
            clearWebViewHttpCache()
            Timber.d("WebView HTTP cache cleared")
        } catch (e: RuntimeException) {
            Timber.e(e, "Failed to clear WebView HTTP cache")
        }
        try {
            clearWebSiteStorage()
            Timber.d("WebView site storage cleared")
        } catch (e: RuntimeException) {
            Timber.e(e, "Failed to clear WebView site storage")
        }
    }

    /**
     * Calculates the total size of the app's cache directory in bytes.
     */
    suspend fun calculateCacheSize(): Long = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        if (cacheDir.exists()) {
            cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
    }
}
