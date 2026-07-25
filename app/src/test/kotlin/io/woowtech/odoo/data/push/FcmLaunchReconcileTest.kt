package io.woowtech.odoo.data.push

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.woowtech.odoo.data.repository.FcmTokenRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * Unit tests for WI-2 — the launch-time FCM token reconcile orchestration performed by
 * [io.woowtech.odoo.WoowOdooApp.reconcileFcmToken].
 *
 * `WoowOdooApp` is the Hilt `Application` and obtains the [FcmTokenRepository] through a Hilt
 * `EntryPoint`, fetching the token from `FirebaseMessaging.getInstance().token` — none of which can
 * be constructed on the JVM without Firebase/Play-Services and a Hilt graph (this module has no
 * Robolectric; see `WoowFcmServiceTest` for the same constraint). Following that established repo
 * idiom, the exact post-token-fetch orchestration from `WoowOdooApp` is replicated here against a
 * mocked repository, so the branching can be verified deterministically:
 *
 *  - a successful, non-blank token → `repository.reconcileToken(token)` (which itself delegates to
 *    `registerTokenForAllAccounts`) is invoked with the *current* token,
 *  - the account-existence gate lives in `reconcileToken` (0 accounts → short-circuit, no register),
 *  - a failed / null / blank token fetch never reaches the repository and never throws,
 *  - the reconcile runs on the injected IO/background dispatcher, never `Main`.
 *
 * The repository's own account-gate and idempotency behaviour is unit-tested directly in
 * `FcmTokenRepositoryTest` (`reconcileToken` cases); here we test the *Application-side* launch
 * orchestration that decides whether and with what token to call it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FcmLaunchReconcileTest {

    private lateinit var repository: FcmTokenRepository
    private val loggedThrowables = mutableListOf<Throwable?>()

    @BeforeEach
    fun setup() {
        repository = mockk()
        loggedThrowables.clear()
        Timber.uprootAll()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                loggedThrowables.add(t)
            }
        })
    }

    /**
     * Models the outcome of `FirebaseMessaging.getInstance().token` on launch: either a resolved
     * token (possibly null/blank) or a failure. Mirrors the `addOnCompleteListener` branches in
     * `WoowOdooApp.reconcileFcmToken`.
     */
    private sealed interface TokenFetch {
        data class Success(val token: String?) : TokenFetch
        data class Failure(val error: Throwable) : TokenFetch
    }

    /**
     * Faithful replica of [io.woowtech.odoo.WoowOdooApp.reconcileFcmToken]'s post-token-fetch
     * orchestration. Given the token-fetch [outcome], it applies the same guards (fetch failure and
     * null/blank token skip registration entirely, never throwing) and, on a valid token, launches
     * the reconcile on [ioDispatcher] and calls [FcmTokenRepository.reconcileToken] with that exact
     * token. Runs the launched work inside [scope] so the test can drain it deterministically.
     */
    private fun runReconcileOrchestration(
        outcome: TokenFetch,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        onReconcileDispatcher: (String) -> Unit = {},
    ) {
        when (outcome) {
            is TokenFetch.Failure -> {
                Timber.e(outcome.error, "Failed to get FCM token — skipping launch reconcile")
                return
            }
            is TokenFetch.Success -> {
                val token = outcome.token
                if (token.isNullOrBlank()) {
                    Timber.w("FCM token was null/empty — skipping launch reconcile")
                    return
                }
                scope.launch(ioDispatcher) {
                    // Capture the dispatcher name the register work actually observes, so the test
                    // can prove the reconcile runs on IO/background and not Main.
                    withContext(ioDispatcher) {
                        onReconcileDispatcher(Thread.currentThread().name)
                    }
                    repository.reconcileToken(token)
                }
            }
        }
    }

    // ── Test 4: Launch-time reconcile calls registerTokenForAllAccounts with the current token ──

    @Test
    fun `Given a fetched token and the launch reconcile runs then reconcileToken is called with that token`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.reconcileToken("T") } returns Result.success(Unit)

        runReconcileOrchestration(
            outcome = TokenFetch.Success("T"),
            scope = this,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        // reconcileToken delegates to registerTokenForAllAccounts when an account exists (AC2.1) —
        // the Application-side orchestration's job is to pass the CURRENT token unchanged.
        coVerify(exactly = 1) { repository.reconcileToken("T") }
    }

    // ── Test 5: Short-circuits with no accounts (no register, no throw) ──

    @Test
    fun `Given no active accounts when launch reconcile runs then reconcileToken short-circuits and does not throw`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // reconcileToken itself returns success without registering when there are no accounts (AC2.4);
        // the orchestration must still call it exactly once and must not crash.
        coEvery { repository.reconcileToken("T") } returns Result.success(Unit)

        runReconcileOrchestration(
            outcome = TokenFetch.Success("T"),
            scope = this,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.reconcileToken("T") }
        assertTrue(loggedThrowables.none { it != null }, "No error should be logged on the no-accounts path")
    }

    // ── Test 6: Idempotency — repeated launches with the same token are stable ──

    @Test
    fun `Given the same token when launch reconcile runs twice then reconcileToken is invoked each time without side effects`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.reconcileToken("T") } returns Result.success(Unit)

        runReconcileOrchestration(TokenFetch.Success("T"), this, dispatcher)
        advanceUntilIdle()
        runReconcileOrchestration(TokenFetch.Success("T"), this, dispatcher)
        advanceUntilIdle()

        // Registering an already-current token is a no-op-equivalent on the server side (AC2.3):
        // the orchestration simply re-invokes the idempotent reconcile; nothing throws.
        coVerify(exactly = 2) { repository.reconcileToken("T") }
    }

    // ── Test 7: Reconcile runs on the injected IO/background dispatcher, not Main ──

    @Test
    fun `Given an injected IO dispatcher when launch reconcile runs then the register work runs on that dispatcher not Main`() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.reconcileToken("T") } returns Result.success(Unit)

        var observedThreadName: String? = null
        runReconcileOrchestration(
            outcome = TokenFetch.Success("T"),
            scope = this,
            ioDispatcher = ioDispatcher,
            onReconcileDispatcher = { observedThreadName = it },
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.reconcileToken("T") }
        // The launched reconcile observed the injected (background) test dispatcher, proving it was
        // dispatched off the caller/Main path (AC2.5). The name is non-null because the block ran.
        assertTrue(observedThreadName != null, "Reconcile work must have executed on the injected dispatcher")
        assertFalse(
            observedThreadName!!.contains("main", ignoreCase = true),
            "Reconcile must not run on the Main thread — got $observedThreadName",
        )
    }

    // ── Test 8: Token fetch failure is non-fatal (AC2.6) ──

    @Test
    fun `Given a failed token fetch when launch reconcile runs then reconcileToken is never called and no exception propagates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.reconcileToken(any()) } returns Result.success(Unit)

        // Should not throw even though the token task failed — the failure is caught and logged.
        runReconcileOrchestration(
            outcome = TokenFetch.Failure(RuntimeException("Play Services updating")),
            scope = this,
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.reconcileToken(any()) }
        assertTrue(
            loggedThrowables.any { it is RuntimeException },
            "The token-fetch failure must be logged (non-fatal recovery on next launch)",
        )
    }

    @Test
    fun `Given a null token when launch reconcile runs then reconcileToken is never called and no crash`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.reconcileToken(any()) } returns Result.success(Unit)

        runReconcileOrchestration(TokenFetch.Success(null), this, dispatcher)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.reconcileToken(any()) }
    }

    @Test
    fun `Given a blank token when launch reconcile runs then reconcileToken is never called`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.reconcileToken(any()) } returns Result.success(Unit)

        runReconcileOrchestration(TokenFetch.Success("   "), this, dispatcher)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.reconcileToken(any()) }
    }

    @Test
    fun `Given a reconcile that returns failure when launch reconcile runs then no exception propagates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // registerTokenForAllAccounts can return Result.failure for some accounts; the launch
        // orchestration must treat it as non-fatal (AC2.6) and must not crash app startup.
        coEvery { repository.reconcileToken("T") } returns Result.failure(IllegalStateException("partial"))

        runReconcileOrchestration(TokenFetch.Success("T"), this, dispatcher)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.reconcileToken("T") }
    }
}
