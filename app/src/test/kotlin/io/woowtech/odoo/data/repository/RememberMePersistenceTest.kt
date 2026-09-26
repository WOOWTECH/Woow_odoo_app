package io.woowtech.odoo.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * W1-10（EP-10R 子項 a／T43 修補）：「記住我」沒勾時不得保存密碼。
 *
 * 修補前（`cb4ed19` 以 characterisation test 釘住）：`LoginViewModel` 的 `rememberMe` 只是
 * UI state，`AccountRepository.authenticate()` 沒有對應參數，`savePassword()` 無條件執行。
 * 該檔原本的「簽章不含 Boolean」測試在本次修補時如預期轉紅，已改寫為下列新契約：
 *
 * - `rememberPassword = true`（預設，與勾選預設值一致）→ 保存密碼。
 * - `rememberPassword = false` → **不保存**，並移除同一帳號先前記住的密碼（不留可重用秘密）；
 *   帳號列與登入 session 照常建立。WOOW 與 Apporo 兩條路徑都適用；Apporo 本地提交失敗時
 *   仍回滾到原本的密碼。
 * - 通知深層連結：active 帳號不需保存密碼也能接收；非 active 帳號要切換，仍需保存的密碼。
 *
 * 取捨：未記住密碼的帳號在 session 過期時不會靜默重新認證（`SessionReauthenticator`
 * 會發出重新登入訊號），也無法不輸入密碼就切回；這正是使用者取消「記住我」的意思。
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
            brand = io.woowtech.odoo.brand.AppBrand.forCode("woowtech"),
        )
    }

    private suspend fun login(remember: Boolean) = accountRepository.authenticate(
        serverUrl = "demo.example.invalid",
        database = "db",
        username = "tester",
        password = "irrelevant-fixture-value",
        rememberPassword = remember,
    )

    @Test
    fun `Given remember me checked when authenticate succeeds then password is persisted`() = runTest {
        login(remember = true)

        coVerify(exactly = 1) { encryptedPrefs.savePassword(any(), "irrelevant-fixture-value") }
        coVerify(exactly = 0) { encryptedPrefs.removePassword(any()) }
    }

    @Test
    fun `Given remember me unchecked when authenticate succeeds then no password is persisted and stale one removed`() = runTest {
        val result = login(remember = false)

        assertTrue(result is AuthResult.Success)
        coVerify(exactly = 0) { encryptedPrefs.savePassword(any(), any()) }
        coVerify(exactly = 1) { encryptedPrefs.removePassword(any()) }
        coVerify(exactly = 1) { accountDao.insertAccount(any()) }
    }

    @Test
    fun `Given remember me unchecked when authenticate fails then nothing is written or removed`() = runTest {
        coEvery { odooClient.authenticate(any(), any(), any(), any()) } returns
            AuthResult.Error("bad", AuthResult.ErrorType.INVALID_CREDENTIALS)

        login(remember = false)

        coVerify(exactly = 0) { encryptedPrefs.savePassword(any(), any()) }
        coVerify(exactly = 0) { encryptedPrefs.removePassword(any()) }
    }

    @Test
    fun `Given legacy caller without the flag when authenticate then it keeps remembering like the checkbox default`() = runTest {
        accountRepository.authenticate(
            serverUrl = "demo.example.invalid",
            database = "db",
            username = "tester",
            password = "irrelevant-fixture-value",
        )

        coVerify(exactly = 1) { encryptedPrefs.savePassword(any(), any()) }
    }

    @Test
    fun `Given authenticate signature when inspected then it carries the user persistence intent`() {
        // suspend 函式在 bytecode 上多一個尾端 Continuation：4 String + 1 Boolean + 1 Continuation。
        val authenticate = AccountRepository::class.java.methods.single { it.name == "authenticate" }
        val paramTypes = authenticate.parameterTypes

        assertEquals(6, paramTypes.size)
        assertEquals(java.lang.Boolean.TYPE, paramTypes[4], "第 5 個參數必須是 rememberPassword: Boolean")
    }

    // ── Apporo 路徑（isolated session 提交邊界）──────────────────────────────

    private fun apporoRepository(): AccountRepository {
        coEvery { odooClient.authenticateApporoIsolated(any(), any(), any(), any()) } returns
            AuthResult.Success(userId = 7, sessionId = "apporo-session", username = "tester", displayName = "Tester")
        coEvery { accountDao.findAccount(any(), any(), any()) } returns null
        coEvery { accountDao.getActiveAccountOnce() } returns null
        return AccountRepository(accountDao, encryptedPrefs, odooClient, io.woowtech.odoo.brand.AppBrand.forCode("apporo"))
    }

    @Test
    fun `Given Apporo and remember me unchecked when login commits then session is published without saving password`() = runTest {
        val repo = apporoRepository()

        val result = repo.authenticate("demo.example.invalid", "db", "tester", "irrelevant-fixture-value", rememberPassword = false)

        assertTrue(result is AuthResult.Success)
        coVerify(exactly = 0) { encryptedPrefs.savePassword(any(), any()) }
        coVerify(exactly = 1) { encryptedPrefs.removePassword(any()) }
        coVerify(exactly = 1) { odooClient.publishApporoSession("https://demo.example.invalid", "apporo-session") }
    }

    @Test
    fun `Given Apporo and remember me checked when login commits then password is saved`() = runTest {
        val repo = apporoRepository()

        repo.authenticate("demo.example.invalid", "db", "tester", "irrelevant-fixture-value", rememberPassword = true)

        coVerify(exactly = 1) { encryptedPrefs.savePassword(any(), "irrelevant-fixture-value") }
        coVerify(exactly = 0) { encryptedPrefs.removePassword(any()) }
    }

    @Test
    fun `Given Apporo unchecked login whose local commit fails then previously remembered password is restored`() = runTest {
        val repo = apporoRepository()
        every { encryptedPrefs.getPassword(any()) } returns "previous-fixture-value"
        every { odooClient.publishApporoSession(any(), any()) } throws IllegalStateException("publish failed")

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.authenticate("demo.example.invalid", "db", "tester", "irrelevant-fixture-value", rememberPassword = false)
            }
        }

        coVerify(exactly = 1) { encryptedPrefs.removePassword(any()) }
        coVerify(exactly = 1) { encryptedPrefs.savePassword(any(), "previous-fixture-value") }
    }

    // ── 讀回端 ─────────────────────────────────────────────────────────────

    /**
     * `isLoggedIn()` 仍僅憑「有沒有存密碼」判定（可切換、可靜默重新認證的帳號）。
     */
    @Test
    fun `Given a persisted password when isLoggedIn then it reports logged in purely from storage`() {
        coEvery { encryptedPrefs.getPassword("acc-1") } returns "stored-fixture-value"
        coEvery { encryptedPrefs.getPassword("acc-absent") } returns null

        assertEquals(true, accountRepository.isLoggedIn("acc-1"))
        assertEquals(false, accountRepository.isLoggedIn("acc-absent"))
    }

    @Test
    fun `Given active account without remembered password when routing a deep link then it is still routable`() {
        coEvery { encryptedPrefs.getPassword(any()) } returns null
        coEvery { encryptedPrefs.getPassword("other-remembered") } returns "stored-fixture-value"

        assertTrue(accountRepository.canRouteDeepLink("active", activeAccountId = "active"))
        assertFalse(accountRepository.canRouteDeepLink("other-forgotten", activeAccountId = "active"))
        assertTrue(accountRepository.canRouteDeepLink("other-remembered", activeAccountId = "active"))
        assertFalse(accountRepository.canRouteDeepLink("any", activeAccountId = null))
    }
}
