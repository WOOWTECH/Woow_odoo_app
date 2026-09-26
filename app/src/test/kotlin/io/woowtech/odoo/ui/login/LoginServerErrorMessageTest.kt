package io.woowtech.odoo.ui.login

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import io.mockk.coEvery
import io.mockk.mockk
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.api.OdooJsonRpcClient
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.testutil.MockOnlyHttpFixture
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回歸防線：登入遇到伺服器非 200 回應（例：Cloudflare 530 origin 無法連線）時，錯誤卡片要以
 * 在地化字串帶出 HTTP 狀態碼，且只出現一次（與 iOS b9ebd0d `error_server_http_%lld` 一致）。
 *
 * 修正前：Apporo 顯示「伺服器錯誤」而沒有狀態碼；WOOW 路徑把 HTML 本文丟給 JSON 解析，
 * 落入 UNKNOWN，顯示英文例外訊息。Odoo 以 HTTP 200 回傳的 JSON-RPC error 顯示維持不變。
 *
 * 全程走 [MockOnlyHttpFixture]：任何請求在 DNS／socket 前就被攔下，不會連到真主機。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], application = Application::class)
class LoginServerErrorMessageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // setContent may run once per test; each brand gets a fresh ViewModel/screen through this holder.
    private val shown = mutableStateOf<LoginViewModel?>(null)
    private var contentSet = false

    private fun cloudflare530(request: Request): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(530).message("Origin unreachable")
            .body("<html>error code: 1033</html>".toResponseBody("text/html".toMediaType()))
            .build()

    private fun odooJsonRpcError(request: Request): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"jsonrpc":"2.0","id":1,"error":{"code":200,"message":"Odoo Server Error","data":{"name":"builtins.RuntimeError","message":"boom"}}}"""
                .toResponseBody("application/json".toMediaType()))
            .build()

    /** Drives the real LoginScreen → ViewModel → repository → client path and returns every rendered text. */
    private fun renderLoginError(code: String, respond: (Request) -> Response): List<String> {
        val fixture = MockOnlyHttpFixture(respond)
        val brand = AppBrand.forCode(code)
        val api = OdooJsonRpcClient(fixture.client, brand, fixture.client)
        val dao = mockk<AccountDao>(relaxed = true)
        coEvery { dao.findAccount(any(), any(), any()) } returns null
        coEvery { dao.getActiveAccountOnce() } returns null
        val viewModel = LoginViewModel(AccountRepository(dao, mockk<EncryptedPrefs>(relaxed = true), api, brand)).apply {
            updateServerUrl("fixture.test")
            updateDatabase("fixture-db")
            goToNextStep()
            updateUsername("fixture-user")
            updatePassword("mock-password")
        }
        shown.value = viewModel
        if (!contentSet) {
            contentSet = true
            composeRule.setContent {
                shown.value?.let { vm -> key(vm) { LoginScreen(viewModel = vm, onLoginSuccess = {}) } }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { viewModel.login { throw AssertionError("an error response must not sign in") } }
        // The fixture answers on OkHttp's thread; pump the paused main looper until the result lands.
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.uiState.value.let { it.isLoading || it.error == null } && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse("login did not finish: ${viewModel.uiState.value}", viewModel.uiState.value.isLoading)
        composeRule.waitForIdle()
        assertEquals("exactly one mock request, never a real host", 1, fixture.requests.get())
        assertEquals(0, fixture.dnsCalls.get())
        assertEquals(0, fixture.connectCalls.get())
        val texts = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.children.forEach(::walk)
        }
        walk(composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return texts
    }

    private fun assertShownOnce(expected: String, phrase: String, chinese: Boolean) {
        // Render both brands first so a failure report shows what each one displayed.
        val rendered = listOf("woowtech", "apporo").associateWith { renderLoginError(it, ::cloudflare530) }
        for ((code, texts) in rendered) {
            val context = "$rendered"
            assertEquals(context, 1, texts.count { it == expected })
            val all = texts.joinToString("\n")
            assertEquals("status code must appear exactly once: $context", 1, all.split("530").size - 1)
            assertEquals("server-error phrase must appear exactly once: $context", 1, all.split(phrase).size - 1)
            if (chinese) assertFalse("English leaked: $context", all.contains("server", ignoreCase = true))
        }
    }

    private fun assertJsonRpcErrorUnchanged(expected: String) {
        for (code in listOf("woowtech", "apporo")) {
            val texts = renderLoginError(code, ::odooJsonRpcError)
            assertEquals("$code: $texts", 1, texts.count { it == expected })
            assertFalse("$code: $texts", texts.any { it.contains("HTTP") })
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `Given 530 sign-in response in English then error names the status once`() =
        assertShownOnce("Server error (HTTP 530)", "Server error", chinese = false)

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `Given 530 sign-in response in Traditional Chinese then error names the status once`() =
        assertShownOnce("伺服器錯誤（HTTP 530）", "伺服器錯誤", chinese = true)

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Given 530 sign-in response in Simplified Chinese then error names the status once`() =
        assertShownOnce("服务器错误（HTTP 530）", "服务器错误", chinese = true)

    @Test
    @Config(qualifiers = "en")
    fun `Given Odoo JSON-RPC error in English then existing server error text is unchanged`() =
        assertJsonRpcErrorUnchanged("Server error")

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `Given Odoo JSON-RPC error in Traditional Chinese then existing server error text is unchanged`() =
        assertJsonRpcErrorUnchanged("伺服器錯誤")

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Given Odoo JSON-RPC error in Simplified Chinese then existing server error text is unchanged`() =
        assertJsonRpcErrorUnchanged("服务器错误")
}
