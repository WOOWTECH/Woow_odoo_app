package io.woowtech.odoo.ui.config

import io.mockk.coVerify
import io.mockk.mockk
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.CacheRepository
import io.woowtech.odoo.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * EP-10R 子項 c（T33）：「清除快取」的實際語意。
 *
 * ## 這些是 characterisation test
 *
 * 斷言通過代表「**目前行為已被鎖定並記錄**」。minimal-remediation §10 把
 * **cache scope（只清 active 實例還是全部實例）列為 owner 未決**，因此本票
 * **只重現、不改**。
 *
 * ## 實查結果（2026-09-19，file:line）
 *
 * `ui/config/SettingsViewModel.kt:109-121` 的 `clearCache()` 做兩件事：
 *   1. `cacheRepository.clearAppCache()`
 *   2. `cacheRepository.clearWebViewCache()`
 *
 * `data/repository/CacheRepository.kt:24-31` `clearAppCache()`
 *   → `context.cacheDir.deleteRecursively()`（App 自身快取目錄，合理）
 *
 * `data/repository/CacheRepository.kt:36-39` `clearWebViewCache()`
 *   → `WebStorage.getInstance().deleteAllData()`
 *
 * ## ★ 兩個需要 owner 注意的落差
 *
 * **(1) 清掉的東西比「快取」更多。**
 * `WebStorage.deleteAllData()` 清的是 **localStorage / IndexedDB / Web SQL**，
 * 也就是網頁的**持久性儲存**，而非 HTTP 快取。原始碼**沒有**呼叫
 * `WebView.clearCache()`，所以真正的 HTTP 快取其實沒被清。
 * 這與 §10 明文的「cache 補丁不得清 cookie 或 localStorage 來假裝完成」
 * 方向相反 —— 現行實作正是以清 localStorage 充當清快取。
 *
 * **(2) 作用範圍是全域，不分實例。**
 * `deleteAllData()` 對**所有 origin** 生效，因此多實例下會一併清掉其他
 * 已保存實例的網頁儲存。scope 正是 owner 未決事項。
 *
 * ## 有守住的部分
 *
 * Cookie 未被觸碰（全檔無 `CookieManager`），所以「清快取 ≠ 登出」成立；
 * 憑證亦未被刪除，見下方測試。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheClearSemanticsTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var cacheRepository: CacheRepository
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsRepository = mockk(relaxed = true)
        cacheRepository = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        viewModel = SettingsViewModel(settingsRepository, cacheRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * T33 核心正向契約：**清快取不等於登出**。
     *
     * 清除動作不得刪除任何已保存憑證 —— 否則使用者會在以為「只是清快取」時
     * 被登出所有實例。
     */
    @Test
    fun `Given clearCache when invoked then no stored credential is removed`() = runTest {
        viewModel.clearCache()

        coVerify(exactly = 0) { encryptedPrefs.removePassword(any()) }
        coVerify(exactly = 0) { encryptedPrefs.savePassword(any(), any()) }
    }

    /**
     * 現況鎖定：clearCache() 同時清 App 快取與 WebView 儲存。
     *
     * 第二項就是上方 §落差(1)(2) 所指 —— 它實際清的是 localStorage 等
     * 持久性網頁儲存，且對所有 origin 生效。此處鎖定呼叫事實，
     * 待 owner 決定 scope 與正確的清除對象後再調整。
     */
    @Test
    fun `Given clearCache when invoked then both app cache and webview storage are cleared`() = runTest {
        viewModel.clearCache()

        coVerify(exactly = 1) { cacheRepository.clearAppCache() }
        coVerify(exactly = 1) { cacheRepository.clearWebViewCache() }
    }

    /**
     * 現況鎖定：清除後會重新計算並回報快取大小。
     *
     * 這是使用者看得到的回饋，也是「清除確實執行過」的唯一 UI 訊號。
     */
    @Test
    fun `Given clearCache when invoked then cache size is recalculated for display`() = runTest {
        viewModel.clearCache()

        // exactly = 2：`SettingsViewModel.kt:27-31` 的 init 區塊在建構時已計算一次，
        // `clearCache()`（:109-121）清除後再計算一次。第一版誤寫 exactly = 1 而失敗，
        // 實測後修正 —— 這是測試預期錯誤，非產品缺陷。
        coVerify(exactly = 2) { cacheRepository.calculateCacheSize() }
        assertTrue(
            viewModel.cacheSizeText.value.isNotEmpty(),
            "清除後應更新可顯示的快取大小文字",
        )
    }
}
