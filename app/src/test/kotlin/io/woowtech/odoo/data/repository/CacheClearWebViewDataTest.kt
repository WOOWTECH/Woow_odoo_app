package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * W1-10（EP-10R T33 修補）：「清除快取」必須清 WebView HTTP 快取與網站資料，且不刪帳號。
 *
 * 修補前（`cb4ed19` 釘住的現況）`clearWebViewCache()` 只呼叫
 * `WebStorage.deleteAllData()`，從未呼叫 `WebView.clearCache(true)`，HTTP 快取沒被清。
 * 本檔以 [CacheRepository] 的函式注入點驗證：兩項都執行、一項失敗不影響另一項，
 * 而且帳號資料庫、加密偏好（密碼）、cookie 所在目錄都不被碰。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheClearWebViewDataTest {

    @TempDir
    lateinit var dataDir: File

    private lateinit var context: android.content.Context
    private val calls = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        every { context.cacheDir } returns File(dataDir, "cache").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repo(
        http: () -> Unit = { calls += "http-cache" },
        storage: () -> Unit = { calls += "site-storage" },
    ) = CacheRepository(context, clearWebViewHttpCache = http, clearWebSiteStorage = storage)

    @Test
    fun `Given clearWebViewCache when invoked then HTTP cache and site storage are both cleared once`() = runTest {
        repo().clearWebViewCache()

        assertEquals(listOf("http-cache", "site-storage"), calls)
    }

    @Test
    fun `Given HTTP cache clear throws when clearWebViewCache then site storage is still cleared`() = runTest {
        repo(http = { throw IllegalStateException("WebView provider missing") }).clearWebViewCache()

        assertEquals(listOf("site-storage"), calls)
    }

    @Test
    fun `Given site storage clear throws when clearWebViewCache then HTTP cache clear still ran and no crash`() = runTest {
        repo(storage = { throw IllegalStateException("storage unavailable") }).clearWebViewCache()

        assertEquals(listOf("http-cache"), calls)
    }

    @Test
    fun `Given account and credential stores when cache cleared then they survive`() = runTest {
        // Sibling app-data dirs that hold the Room accounts DB, EncryptedSharedPreferences
        // (passwords) and the WebView cookie jar. Clearing the cache must not remove any of them.
        val cache = context.cacheDir
        File(cache, "WebView/Default/HTTP Cache").apply { mkdirs() }.resolve("entry").writeText("x")
        val survivors = listOf(
            File(dataDir, "databases/woow_odoo.db"),
            File(dataDir, "shared_prefs/woow_secure_prefs.xml"),
            File(dataDir, "app_webview/Default/Cookies"),
        ).onEach { it.parentFile!!.mkdirs(); it.writeText("keep") }

        val repository = repo()
        repository.clearAppCache()
        repository.clearWebViewCache()

        survivors.forEach { assertTrue(it.isFile, "${it.relativeTo(dataDir)} must survive clear cache") }
        assertFalse(File(cache, "WebView/Default/HTTP Cache/entry").exists())
        assertEquals(listOf("http-cache", "site-storage"), calls)
    }

    @Test
    fun `Given production source when inspected then clear cache never touches cookies or credentials`() {
        val source = listOf(
            "src/main/java/io/woowtech/odoo/data/repository/CacheRepository.kt",
            "app/src/main/java/io/woowtech/odoo/data/repository/CacheRepository.kt",
        ).map(::File).first { it.isFile }.readText()

        assertTrue(source.contains("webView.clearCache(true)"), "HTTP cache (disk too) must be cleared")
        assertTrue(source.contains("WebStorage.getInstance().deleteAllData()"), "site storage must be cleared")
        for (forbidden in listOf("CookieManager", "removeAllCookies", "removePassword", "deleteAccount", "EncryptedPrefs")) {
            assertFalse(source.contains(forbidden), "CacheRepository must not reference $forbidden")
        }
    }
}
