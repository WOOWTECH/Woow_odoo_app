package io.woowtech.odoo.data.repository

import android.content.Context
import android.webkit.WebStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles cache clearing operations without holding a reference to Activity context.
 * Uses WebStorage API instead of creating throwaway WebView instances.
 */
@Singleton
class CacheRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

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
     * Clears WebView storage data without creating a throwaway WebView.
     * Must be called from the main thread.
     */
    suspend fun clearWebViewCache() = withContext(Dispatchers.Main) {
        WebStorage.getInstance().deleteAllData()
        Timber.d("WebView cache cleared")
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
