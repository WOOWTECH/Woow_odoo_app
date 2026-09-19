package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * EP-10R 子項 a（T43）：Remember me 的 UI 契約與實際持久化行為不一致。
 *
 * ## 這些是 characterisation test，不是「行為正確」的宣告
 *
 * 斷言通過代表「**目前行為已被鎖定並記錄**」，不代表該行為符合使用者預期。
 * minimal-remediation §10 把修補方案列為 **owner 未決**（方案 A：未勾選就不存
 * 可重用密碼／方案 B：保留現行行為但改 UI 文案誠實告知），因此本票
 * **只重現、不改產品語意**。
 *
 * ## 缺陷（file:line 佐證，2026-09-19 實查）
 *
 * - `ui/login/LoginViewModel.kt:20` —— `rememberMe: Boolean = true` 存在於 UI state
 * - `ui/login/LoginViewModel.kt:74-75` —— `updateRememberMe()` **只**更新 UI state
 * - `ui/login/LoginViewModel.kt:133-138` —— 呼叫 `authenticate()` 時**未傳遞** rememberMe
 * - `data/repository/AccountRepository.kt:41-45` —— `authenticate()` 簽章**沒有** rememberMe 參數
 * - `data/repository/AccountRepository.kt:74` —— `encryptedPrefs.savePassword()` **無條件**執行
 *
 * 結果：使用者取消勾選 Remember me，密碼**仍然**被持久化，且該密碼是
 * **可重用的** —— 被以下四處讀回用於靜默重新認證：
 * `AccountRepository.kt:39`(isLoggedIn)、`:100`、`ui/main/MainViewModel.kt:69`、
 * `data/api/SessionReauthenticator.kt:127`。
 *
 * ## 為什麼沒有 observed-RED
 *
 * `authenticate()` **根本沒有 rememberMe 參數**。要寫出「傳 false 時不得持久化」
 * 的紅測，就必須先新增該參數 —— 那是改變產品 API 與語意，已被本票明文禁止。
 * 硬寫只會得到**編譯失敗**，不是有意義的測試失敗。
 * 因此這裡採 characterisation：把現況精確釘住，owner 決定方案 A 後，
 * 第三條測試（簽章檢查）會**自動轉紅**，即為現成的紅測。
 *
 * ## 安全性界定
 *
 * 密碼存於 `EncryptedPrefs`（AndroidKeyStore AES-256-GCM/SIV，且已排除於備份）。
 * 這是 **UI 承諾與儲存行為不一致**的契約缺口，**不是**明文外洩或已發生的洩漏事件。
 */
class RememberMePersistenceTest {

    private lateinit var accountDao: AccountDao
    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var odooClient: OdooJsonRpcClient
    private lateinit var accountRepository: AccountRepository

    private val authSuccess = AuthResult.Success(
        userId = 7,
        sessionId = "session-remember-me",
        username = "tester",
        displayName = "Tester",
    )

    @BeforeEach
    fun setup() {
        accountDao = mockk(relaxed = true)
        // 假 secure store：MockK 只記錄「有沒有被呼叫、帶什麼 key」，不保留真實密碼值。
        encryptedPrefs = mockk(relaxed = true)
        odooClient = mockk(relaxed = true)

        coEvery { odooClient.authenticate(any(), any(), any(), any()) } returns authSuccess

        accountRepository = AccountRepository(
            accountDao = accountDao,
            encryptedPrefs = encryptedPrefs,
            odooClient = odooClient,
        )
    }

    /**
     * 現況鎖定：登入成功一律持久化密碼。UI 上的 Remember me 勾選與否對此**毫無影響**，
     * 因為該旗標從未離開 `LoginViewModel` 的 UI state。
     */
    @Test
    fun `Given successful login when authenticate then password is persisted unconditionally`() = runTest {
        accountRepository.authenticate(
            serverUrl = "demo.example.invalid",
            database = "db",
            username = "tester",
            password = "irrelevant-fixture-value",
        )

        coVerify(exactly = 1) { encryptedPrefs.savePassword(any(), any()) }
    }

    /**
     * 現況鎖定：**取消勾選的路徑不存在**。
     *
     * 這條測試用反射檢查 `authenticate()` 的實際參數名稱。目前是
     * `serverUrl / database / username / password` 四個，**沒有任何表達使用者
     * 持久化意圖的參數** —— 這就是 UI 旗標與儲存層之間斷鏈的客觀證據。
     *
     * ★ owner 若採方案 A（未勾選就不存），此處必須新增參數，屆時本測試
     *   **自動轉紅**，即為現成紅測，提醒實作者同步更新本檔的契約描述。
     */
    @Test
    fun `Given authenticate signature when inspected then it carries no user persistence intent`() {
        // 用 Java reflection（測試 classpath 無 kotlin-reflect，且本票不得新增依賴）。
        // suspend 函式在 bytecode 上會多一個尾端的 Continuation 參數，
        // 故 4 個業務參數 + 1 Continuation = 5。
        val authenticate = AccountRepository::class.java.methods
            .single { it.name == "authenticate" }
        val paramTypes = authenticate.parameterTypes

        assertEquals(
            5,
            paramTypes.size,
            "authenticate() 目前是 serverUrl/database/username/password 四個業務參數" +
                "（+1 個 suspend 的 Continuation）。參數個數一旦改變，代表 Remember me " +
                "契約可能已被處理 —— 請同步更新本檔的 characterisation 描述，" +
                "並確認 owner 已核定方案",
        )
        // 四個業務參數全是 String：沒有任何 Boolean 能承載「使用者是否要求持久化」。
        assertEquals(
            4,
            paramTypes.count { it == String::class.java },
            "四個業務參數皆為 String",
        )
        assertFalse(
            paramTypes.any { it == Boolean::class.java || it == java.lang.Boolean::class.java },
            "目前沒有任何 Boolean 參數表達『使用者是否要求持久化』—— " +
                "UI 的 Remember me 勾選框因此無法影響儲存行為（T43 契約缺口）",
        )
    }

    /**
     * 現況鎖定：持久化的密碼是**可重用**的 —— `isLoggedIn()` 僅憑「有沒有存密碼」判定。
     *
     * 這是為什麼 T43 屬契約缺口而非純外觀問題：取消勾選後留下的不是惰性資料，
     * 而是足以在使用者不知情下完成靜默重新認證的憑據
     * （`SessionReauthenticator.kt:127` 讀回同一把 key）。
     */
    @Test
    fun `Given a persisted password when isLoggedIn then it reports logged in purely from storage`() {
        coEvery { encryptedPrefs.getPassword("acc-1") } returns "stored-fixture-value"
        coEvery { encryptedPrefs.getPassword("acc-absent") } returns null

        assertEquals(true, accountRepository.isLoggedIn("acc-1"))
        assertEquals(false, accountRepository.isLoggedIn("acc-absent"))
    }
}
