package io.woowtech.odoo.ui.login

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var viewModel: LoginViewModel

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountRepository = mockk(relaxed = true)
        viewModel = LoginViewModel(accountRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────
    // Initial State
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given fresh ViewModel when created then initial state is SERVER_INFO step with empty fields`() = runTest {
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(LoginStep.SERVER_INFO, state.step)
            assertEquals("", state.serverUrl)
            assertEquals("", state.database)
            assertEquals("", state.username)
            assertEquals("", state.password)
            assertTrue(state.rememberMe)
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertNull(state.serverUrlError)
            assertNull(state.databaseError)
            assertNull(state.usernameError)
            assertNull(state.passwordError)
        }
    }

    // ──────────────────────────────────────────────────────────
    // Field Update Methods
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class FieldUpdates {

        @Test
        fun `Given server URL entered when updateServerUrl then state reflects new URL and clears errors`() = runTest {
            viewModel.updateServerUrl("my.odoo.com")

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("my.odoo.com", state.serverUrl)
                assertNull(state.serverUrlError)
                assertNull(state.error)
            }
        }

        @Test
        fun `Given database entered when updateDatabase then state reflects new database and clears errors`() = runTest {
            viewModel.updateDatabase("production")

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("production", state.database)
                assertNull(state.databaseError)
                assertNull(state.error)
            }
        }

        @Test
        fun `Given username entered when updateUsername then state reflects new username and clears errors`() = runTest {
            viewModel.updateUsername("admin")

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("admin", state.username)
                assertNull(state.usernameError)
                assertNull(state.error)
            }
        }

        @Test
        fun `Given password entered when updatePassword then state reflects new password and clears errors`() = runTest {
            viewModel.updatePassword("secret123")

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("secret123", state.password)
                assertNull(state.passwordError)
                assertNull(state.error)
            }
        }

        @Test
        fun `Given rememberMe toggled when updateRememberMe false then state reflects false`() = runTest {
            viewModel.updateRememberMe(false)

            viewModel.uiState.test {
                assertFalse(awaitItem().rememberMe)
            }
        }

        @Test
        fun `Given previous serverUrlError when updateServerUrl then error is cleared`() = runTest {
            // Trigger a server URL error first
            viewModel.goToNextStep() // blank URL => error
            assertEquals("Server URL is required", viewModel.uiState.value.serverUrlError)

            // Now type a URL — error should clear
            viewModel.updateServerUrl("odoo.example.com")
            assertNull(viewModel.uiState.value.serverUrlError)
        }
    }

    // ──────────────────────────────────────────────────────────
    // Step Navigation: goToNextStep()
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class StepNavigation {

        @Test
        fun `Given blank server URL when goToNextStep then shows serverUrlError`() {
            viewModel.updateDatabase("mydb")
            viewModel.goToNextStep()

            val state = viewModel.uiState.value
            assertEquals(LoginStep.SERVER_INFO, state.step)
            assertEquals("Server URL is required", state.serverUrlError)
        }

        @Test
        fun `Given server URL but blank database when goToNextStep then shows databaseError`() {
            viewModel.updateServerUrl("odoo.example.com")
            viewModel.goToNextStep()

            val state = viewModel.uiState.value
            assertEquals(LoginStep.SERVER_INFO, state.step)
            assertEquals("Database name is required", state.databaseError)
        }

        @Test
        fun `Given http URL when goToNextStep then rejects with HTTPS required error`() {
            viewModel.updateServerUrl("http://insecure.odoo.com")
            viewModel.updateDatabase("mydb")
            viewModel.goToNextStep()

            val state = viewModel.uiState.value
            assertEquals(LoginStep.SERVER_INFO, state.step)
            assertEquals("HTTPS is required for security", state.serverUrlError)
        }

        @Test
        fun `Given valid https URL and database when goToNextStep then advances to CREDENTIALS`() {
            viewModel.updateServerUrl("https://secure.odoo.com")
            viewModel.updateDatabase("production")
            viewModel.goToNextStep()

            assertEquals(LoginStep.CREDENTIALS, viewModel.uiState.value.step)
        }

        @Test
        fun `Given bare domain with no scheme when goToNextStep then advances to CREDENTIALS`() {
            viewModel.updateServerUrl("my.odoo.com")
            viewModel.updateDatabase("mydb")
            viewModel.goToNextStep()

            // Bare domain (no http:// prefix) should be accepted
            assertEquals(LoginStep.CREDENTIALS, viewModel.uiState.value.step)
        }

        @Test
        fun `Given validation errors exist and both fields blank when goToNextStep then only first error shown`() {
            // Both fields blank — should show serverUrl error first (checked first)
            viewModel.goToNextStep()

            val state = viewModel.uiState.value
            assertEquals("Server URL is required", state.serverUrlError)
            // Database error is not set because validation short-circuits
            assertNull(state.databaseError)
        }
    }

    // ──────────────────────────────────────────────────────────
    // goBack()
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given on CREDENTIALS step when goBack then returns to SERVER_INFO and clears credential errors`() {
        viewModel.updateServerUrl("odoo.example.com")
        viewModel.updateDatabase("mydb")
        viewModel.goToNextStep()
        assertEquals(LoginStep.CREDENTIALS, viewModel.uiState.value.step)

        viewModel.goBack()

        val state = viewModel.uiState.value
        assertEquals(LoginStep.SERVER_INFO, state.step)
        assertNull(state.error)
        assertNull(state.passwordError)
        assertNull(state.usernameError)
    }

    // ──────────────────────────────────────────────────────────
    // login() — Validation
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class LoginValidation {

        @Test
        fun `Given blank username when login then shows usernameError without calling repository`() = runTest {
            viewModel.updateServerUrl("odoo.example.com")
            viewModel.updateDatabase("mydb")
            viewModel.goToNextStep()
            viewModel.updatePassword("secret")

            viewModel.login {}

            assertEquals("Username is required", viewModel.uiState.value.usernameError)
            coVerify(exactly = 0) { accountRepository.authenticate(any(), any(), any(), any()) }
        }

        @Test
        fun `Given blank password when login then shows passwordError without calling repository`() = runTest {
            viewModel.updateServerUrl("odoo.example.com")
            viewModel.updateDatabase("mydb")
            viewModel.goToNextStep()
            viewModel.updateUsername("admin")

            viewModel.login {}

            assertEquals("Password is required", viewModel.uiState.value.passwordError)
            coVerify(exactly = 0) { accountRepository.authenticate(any(), any(), any(), any()) }
        }
    }

    // ──────────────────────────────────────────────────────────
    // login() — HTTPS Prefix Behavior
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class LoginHttpsHandling {

        @Test
        fun `Given bare domain when login then prepends https and calls repository`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Success(
                userId = 1,
                sessionId = "abc",
                username = "admin",
                displayName = "Admin"
            )

            viewModel.updateServerUrl("my.odoo.com")
            viewModel.updateDatabase("prod")
            viewModel.goToNextStep()
            viewModel.updateUsername("admin")
            viewModel.updatePassword("pass")

            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                accountRepository.authenticate(
                    serverUrl = "https://my.odoo.com",
                    database = "prod",
                    username = "admin",
                    password = "pass"
                )
            }
        }

        @Test
        fun `Given URL already has https when login then does not double-prefix`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Success(
                userId = 1,
                sessionId = "abc",
                username = "admin",
                displayName = "Admin"
            )

            viewModel.updateServerUrl("https://my.odoo.com")
            viewModel.updateDatabase("prod")
            viewModel.goToNextStep()
            viewModel.updateUsername("admin")
            viewModel.updatePassword("pass")

            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                accountRepository.authenticate(
                    serverUrl = "https://my.odoo.com",
                    database = "prod",
                    username = "admin",
                    password = "pass"
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // login() — Success
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given valid credentials when login succeeds then isLoading clears and onSuccess callback invoked`() = runTest {
        coEvery {
            accountRepository.authenticate(any(), any(), any(), any())
        } returns AuthResult.Success(
            userId = 42,
            sessionId = "session-abc",
            username = "admin",
            displayName = "Admin User"
        )

        viewModel.updateServerUrl("odoo.example.com")
        viewModel.updateDatabase("prod")
        viewModel.goToNextStep()
        viewModel.updateUsername("admin")
        viewModel.updatePassword("pass123")

        var successCalled = false
        viewModel.login { successCalled = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(successCalled, "onSuccess callback should have been invoked")
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    // ──────────────────────────────────────────────────────────
    // login() — Error Mapping
    // ──────────────────────────────────────────────────────────

    @Nested
    inner class LoginErrorMapping {

        private fun setupLoginFlow() {
            viewModel.updateServerUrl("odoo.example.com")
            viewModel.updateDatabase("prod")
            viewModel.goToNextStep()
            viewModel.updateUsername("admin")
            viewModel.updatePassword("wrong")
        }

        @Test
        fun `Given network error when login then shows unable to connect message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("timeout", AuthResult.ErrorType.NETWORK_ERROR)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Unable to connect to server", state.error)
            assertFalse(state.isLoading)
        }

        @Test
        fun `Given invalid URL error when login then shows invalid server URL message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("bad url", AuthResult.ErrorType.INVALID_URL)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Invalid server URL", viewModel.uiState.value.error)
        }

        @Test
        fun `Given database not found error when login then shows database not found message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("no db", AuthResult.ErrorType.DATABASE_NOT_FOUND)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Database not found", viewModel.uiState.value.error)
        }

        @Test
        fun `Given invalid credentials error when login then shows invalid username or password message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("bad creds", AuthResult.ErrorType.INVALID_CREDENTIALS)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Invalid username or password", viewModel.uiState.value.error)
        }

        @Test
        fun `Given session expired error when login then shows session expired message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("expired", AuthResult.ErrorType.SESSION_EXPIRED)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Session expired", viewModel.uiState.value.error)
        }

        @Test
        fun `Given HTTPS required error when login then shows secure connection required message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("https", AuthResult.ErrorType.HTTPS_REQUIRED)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Secure connection required (HTTPS)", viewModel.uiState.value.error)
        }

        @Test
        fun `Given server error when login then shows server error message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("500", AuthResult.ErrorType.SERVER_ERROR)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Server error", viewModel.uiState.value.error)
        }

        @Test
        fun `Given unknown error when login then shows the original error message`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("Something weird happened", AuthResult.ErrorType.UNKNOWN)

            setupLoginFlow()
            viewModel.login {}
            testDispatcher.scheduler.advanceUntilIdle()

            // UNKNOWN type passes through the original message
            assertEquals("Something weird happened", viewModel.uiState.value.error)
        }

        @Test
        fun `Given login error when onSuccess callback should NOT be invoked`() = runTest {
            coEvery {
                accountRepository.authenticate(any(), any(), any(), any())
            } returns AuthResult.Error("fail", AuthResult.ErrorType.INVALID_CREDENTIALS)

            setupLoginFlow()
            var successCalled = false
            viewModel.login { successCalled = true }
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(successCalled, "onSuccess should not be called on auth failure")
        }
    }

    // ──────────────────────────────────────────────────────────
    // clearError()
    // ──────────────────────────────────────────────────────────

    @Test
    fun `Given error present when clearError then error is null`() = runTest {
        coEvery {
            accountRepository.authenticate(any(), any(), any(), any())
        } returns AuthResult.Error("fail", AuthResult.ErrorType.NETWORK_ERROR)

        viewModel.updateServerUrl("odoo.example.com")
        viewModel.updateDatabase("db")
        viewModel.goToNextStep()
        viewModel.updateUsername("admin")
        viewModel.updatePassword("pass")
        viewModel.login {}
        testDispatcher.scheduler.advanceUntilIdle()

        // Error should exist
        assertEquals("Unable to connect to server", viewModel.uiState.value.error)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }
}
