package io.woowtech.odoo.data.api

import io.mockk.*
import io.woowtech.odoo.brand.AppBrand
import io.woowtech.odoo.data.local.AccountDao
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.testutil.MockOnlyHttpFixture
import io.woowtech.odoo.ui.login.LoginViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthTransportIsolationTest {
    @Test
    fun `Given unexpected shared auth request in either brand then block before DNS and connect`() {
        for (code in listOf("woowtech", "apporo")) {
            val fixture = MockOnlyHttpFixture()
            val api = OdooJsonRpcClient(fixture.client, AppBrand.forCode(code), fixture.client)
            assertThrows(AssertionError::class.java) {
                runBlocking { api.authenticate("https://unexpected.fixture.test", "db", "user", "mock-password") }
            }
            assertEquals(1, fixture.requests.get())
            assertEquals(0, fixture.dnsCalls.get())
            assertEquals(0, fixture.connectCalls.get())
        }
    }

    @Test
    fun `Given unexpected isolated auth request then inherited fixture blocks before DNS and connect`() {
        val fixture = MockOnlyHttpFixture()
        val api = OdooJsonRpcClient(fixture.client, AppBrand.forCode("apporo"), fixture.client)
        assertThrows(AssertionError::class.java) {
            runBlocking { api.authenticateApporoIsolated("https://unexpected.fixture.test", "db", "user", "mock-password") }
        }
        assertEquals(1, fixture.requests.get())
        assertEquals(0, fixture.dnsCalls.get())
        assertEquals(0, fixture.connectCalls.get())
    }

    @Test
    fun `Given WOOW UI URL with trailing whitespace then normalize and login successfully through mock API`() = normalizedLogin("woowtech")

    @Test
    fun `Given Apporo UI URL with trailing whitespace then normalize and login successfully through mock API`() = normalizedLogin("apporo")

    private fun normalizedLogin(code: String) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = MockOnlyHttpFixture { request ->
                // Anything else fails before DNS/connect; no real host can be contacted.
                assertEquals("https://fixture.test/web/session/authenticate", request.url.toString())
                assertEquals("POST", request.method)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .addHeader("Set-Cookie", "session_id=mock-session; Secure; Path=/; HttpOnly")
                    .body("""{"result":{"uid":11,"name":"Fixture"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            val brand = AppBrand.forCode(code)
            val api = OdooJsonRpcClient(fixture.client, brand, fixture.client)
            val dao = mockk<AccountDao>(relaxed = true)
            val prefs = mockk<EncryptedPrefs>(relaxed = true)
            coEvery { dao.findAccount(any(), any(), any()) } returns null
            coEvery { dao.getActiveAccountOnce() } returns null
            val repo = AccountRepository(dao, prefs, api, brand)
            val viewModel = LoginViewModel(repo)
            viewModel.updateServerUrl(" https://fixture.test  ")
            viewModel.updateDatabase(" fixture-db ")
            viewModel.goToNextStep()
            assertEquals("fixture.test", viewModel.uiState.value.serverUrl)
            viewModel.updateUsername(" fixture-user ")
            viewModel.updatePassword("mock-password")
            val success = CompletableDeferred<Unit>()
            viewModel.login { success.complete(Unit) }
            success.await()
            assertNull(viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
            coVerify(exactly = 1) { dao.insertAccount(match {
                it.serverUrl == "https://fixture.test" && it.database == "fixture-db" && it.username == "fixture-user"
            }) }
            assertEquals(1, fixture.requests.get())
            assertEquals(0, fixture.dnsCalls.get())
            assertEquals(0, fixture.connectCalls.get())
        } finally { Dispatchers.resetMain() }
    }
}
