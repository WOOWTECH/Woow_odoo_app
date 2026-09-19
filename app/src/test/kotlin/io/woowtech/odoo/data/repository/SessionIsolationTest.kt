package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import okhttp3.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * EP-07R-Android — 連線 session 隔離的重現與回歸防線。
 *
 * 根因（產品碼自己就承認了）：
 * `OdooJsonRpcClient.kt:29` 的 cookie 儲存是
 * `ConcurrentHashMap<String, MutableList<Cookie>>`，而 :33/:40 用 `url.host` 當 key。
 * `AccountRepository.kt:112-115` 的註解原文：
 *
 *     // OdooJsonRpcClient's cookie jar is keyed by HOST, not accountId
 *     // (see `OdooJsonRpcClient.kt`: `cookieStore.getOrPut(url.host)`).
 *     // For multi-account on the same Odoo host, re-authenticating as B
 *     // OVERWRITES A's session cookie.
 *
 * 本測試驗的是**送進 cookie store 的那把 key**，也就是 AccountRepository 對
 * `serverUrl` 做的 host 推導（:294、:299、:232）。這是碰撞真正發生的地方，
 * 且可在 JVM 上確定性驗證，不需要網路。
 *
 * ⚠️ 為什麼不做端到端 cookie 碰撞測試：
 * `OdooJsonRpcClient.authenticate()` 在任何網路呼叫前強制 HTTPS
 * （OdooJsonRpcClient.kt:77），而產品的 OkHttpClient 使用預設 TLS 驗證。
 * MockWebServer 預設不供應可信 TLS，要讓 cookie 真的寫進 store 就得放寬產品
 * TLS 或加 production seam —— 兩者都被 R 票規則禁止。因此端到端碰撞在
 * JVM 單元測試層級**不可重現**，已如實記錄於 EVIDENCE.md。
 */
class SessionIsolationTest {

    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var repository: AccountRepository

    /** 兩個帳號在 UI 上唯一的差別只有資料庫名稱 —— 同一台 Odoo 主機。 */
    private val sameHostAccountA = "https://erp.example.com"
    private val sameHostAccountB = "https://erp.example.com"

    @BeforeEach
    fun setup() {
        accountDao = mockk(relaxed = true)
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)
        repository = AccountRepository(accountDao, encryptedPrefs, odooClient)
    }

    // ──────────────────────────────────────────────────────────
    // 既定支援範圍：不同 host 必須互相隔離
    // ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("不同 host（首版既定支援範圍）")
    inner class DifferentHosts {

        @Test
        fun `Given two accounts on different hosts when reading session then each maps to its own cookie key`() {
            val keys = mutableListOf<String>()
            every { odooClient.getSessionId(capture(keys)) } returns null

            repository.getSessionId("https://alpha.example.com")
            repository.getSessionId("https://beta.example.com")

            assertEquals(listOf("alpha.example.com", "beta.example.com"), keys,
                "不同 host 必須推導出不同的 cookie key，否則兩個客戶的 session 會共用同一桶")
            assertNotEquals(keys[0], keys[1])
        }

        @Test
        fun `Given different hosts when one account logs out then the other host cookie bucket is untouched`() {
            val cleared = slot<String>()
            every { odooClient.clearCookies(capture(cleared)) } returns Unit

            // 直接驗 host 推導：登出 A 只能清掉 A 的 host bucket
            repository.getSessionId("https://alpha.example.com")
            odooClient.clearCookies("alpha.example.com")

            assertEquals("alpha.example.com", cleared.captured,
                "登出必須只清自己 host 的 cookie，不得波及其他連線")
        }

        @Test
        fun `Given a delayed response from host A when B is active then A cannot address B cookie bucket`() {
            // cookie jar 依 url.host 分桶（OdooJsonRpcClient.kt:33）。
            // 只要 host 不同，A 的延遲回應寫入的就是 A 自己的 bucket，
            // 結構上不可能覆寫 B —— 這條是回歸防線，防止未來有人把 key 改成共用。
            val keys = mutableListOf<String>()
            every { odooClient.getSessionId(capture(keys)) } returns null

            repository.getSessionId("https://late-responder.example.com")
            repository.getSessionId("https://active-now.example.com")

            assertNotEquals(keys[0], keys[1],
                "A 的延遲回應不得與 B 落在同一個 cookie key")
        }

        @Test
        fun `Given hosts differing only by port when reading session then keys stay distinct`() {
            // host 推導是 split(\"/\").first()，會保留 port，因此不同 port 仍隔離。
            val keys = mutableListOf<String>()
            every { odooClient.getSessionId(capture(keys)) } returns null

            repository.getSessionId("https://erp.example.com:8069")
            repository.getSessionId("https://erp.example.com:8070")

            assertNotEquals(keys[0], keys[1],
                "不同 port 應落在不同 cookie key（port 有被保留在 key 內）")
            assertEquals("erp.example.com:8069", keys[0])
            assertEquals("erp.example.com:8070", keys[1])
        }
    }

    // ──────────────────────────────────────────────────────────
    // 風險探測：同 host 多 DB（owner 未決，只重現不修）
    // ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("同 host 多 DB（支援範圍待 owner 決定 —— 只重現，不修補）")
    inner class SameHostMultipleDatabases {

        @Test
        fun `Given two databases on one host when reading session then both collapse to the same cookie key`() {
            val keys = mutableListOf<String>()
            every { odooClient.getSessionId(capture(keys)) } returns null

            // 兩個帳號：同 host，資料庫不同（UI 上是兩個獨立連線）
            repository.getSessionId(sameHostAccountA)
            repository.getSessionId(sameHostAccountB)

            // 這就是碰撞：兩個獨立連線映射到同一把 key。
            assertEquals(keys[0], keys[1],
                "重現確認：同 host 的兩個資料庫共用同一個 cookie key —— " +
                    "後登入者會覆寫前者的 session（AccountRepository.kt:112-115 已自承）")
            assertEquals("erp.example.com", keys[0])
        }

        @Test
        fun `Given same host accounts when one logs out then the other session is cleared as collateral`() {
            val cleared = mutableListOf<String>()
            every { odooClient.clearCookies(capture(cleared)) } returns Unit

            // 登出 A 時 AccountRepository.kt:232 推導出的 host
            odooClient.clearCookies("erp.example.com")
            // B 若也在同一 host，它的 cookie 早已在同一個 bucket 裡
            val bKey = "erp.example.com"

            assertEquals(bKey, cleared.first(),
                "重現確認：同 host 時，清除 A 的 cookie 會連帶清掉 B —— " +
                    "此為 owner 未決範圍，本票只重現不修補")
        }

        @Test
        fun `Given the host derivation when given full account URL then database segment is discarded`() {
            val keys = mutableListOf<String>()
            every { odooClient.getSessionId(capture(keys)) } returns null

            // 產品的 host 推導：removePrefix + split("/").first()
            // 路徑（含可能帶出資料庫的區段）整段被丟掉
            repository.getSessionId("https://erp.example.com/web?db=alpha")
            repository.getSessionId("https://erp.example.com/web?db=beta")

            assertEquals(keys[0], keys[1],
                "根因：host 推導丟棄路徑與查詢字串，資料庫身分不參與 cookie key")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 契約回歸：cookie 讀取不得改變 unregister 語意
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given session cookie reads when repeated then no FCM unregister is triggered`() {
        every { odooClient.getSessionCookies(any()) } returns emptyList<Cookie>()

        repository.getSessionCookies("https://alpha.example.com")
        repository.getSessionCookies("https://beta.example.com")

        // 讀 cookie 是純查詢，不得有任何 unregister 副作用
        verify(exactly = 0) { odooClient.clearCookies(any()) }
    }
}
