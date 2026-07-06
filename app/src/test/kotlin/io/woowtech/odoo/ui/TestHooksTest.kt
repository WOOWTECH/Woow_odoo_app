package io.woowtech.odoo.ui

import android.content.Intent
import android.os.Bundle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * Unit tests for [TestHooks.applyIfPresent].
 *
 * BuildConfig.DEBUG is true in the debug test variant, so the DEBUG gate is open
 * for all tests and we verify input-validation and delegation behaviour directly.
 *
 * [Intent] is an Android framework class that cannot be constructed on the JVM
 * without Robolectric. All Intent instances are therefore mocked with MockK so
 * that [Intent.getStringExtra], [Intent.hasExtra], and [Intent.getBooleanExtra]
 * return the values each test scenario needs.
 *
 * Timber is planted with a list-recording tree in setUp so we can assert on
 * warning messages logged for invalid input paths.
 *
 * [SettingsRepository.setPin] is now `suspend` so tests that verify it is called
 * use [coEvery]/[coVerify] and call [advanceUntilIdle] to drain the launched coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestHooksTest {

    private lateinit var settings: SettingsRepository
    private val loggedMessages = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        settings = mockk(relaxed = true)
        loggedMessages.clear()
        Timber.uprootAll()
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                loggedMessages.add(message)
            }
        })
    }

    // ─── Test 1: Valid 4-digit PIN is seeded ───────────────────────────────────

    @Test
    fun `Given valid test-pin 1234 when applyIfPresent then setPin called`() = runTest {
        val intent = intentWithExtras(pin = "1234")
        coEvery { settings.setPin(any()) } returns true

        TestHooks.applyIfPresent(intent, settings, this)
        advanceUntilIdle()

        coVerify { settings.setPin("1234") }
    }

    // ─── Test 2: Non-digit PIN is skipped ─────────────────────────────────────

    @Test
    fun `Given test-pin abc when applyIfPresent then setPin NOT called and warning logged`() = runTest {
        val intent = intentWithExtras(pin = "abc")

        TestHooks.applyIfPresent(intent, settings, this)
        advanceUntilIdle()

        coVerify(exactly = 0) { settings.setPin(any()) }
        assert(loggedMessages.any { it.contains("invalid test-pin", ignoreCase = true) }) {
            "Expected an invalid-pin warning log. Got: $loggedMessages"
        }
    }

    // ─── Test 3: PIN too short is skipped ─────────────────────────────────────

    @Test
    fun `Given test-pin 12 too short when applyIfPresent then setPin NOT called`() = runTest {
        val intent = intentWithExtras(pin = "12")

        TestHooks.applyIfPresent(intent, settings, this)
        advanceUntilIdle()

        coVerify(exactly = 0) { settings.setPin(any()) }
        assert(loggedMessages.any { it.contains("invalid test-pin", ignoreCase = true) }) {
            "Expected an invalid-pin warning log. Got: $loggedMessages"
        }
    }

    // ─── Test 4: PIN too long is skipped ──────────────────────────────────────

    @Test
    fun `Given test-pin 1234567 too long when applyIfPresent then setPin NOT called`() = runTest {
        val intent = intentWithExtras(pin = "1234567")

        TestHooks.applyIfPresent(intent, settings, this)
        advanceUntilIdle()

        coVerify(exactly = 0) { settings.setPin(any()) }
        assert(loggedMessages.any { it.contains("invalid test-pin", ignoreCase = true) }) {
            "Expected an invalid-pin warning log. Got: $loggedMessages"
        }
    }

    // ─── Test 5: app-lock-enabled=true delegates correctly ────────────────────

    @Test
    fun `Given app-lock-enabled=true when applyIfPresent then updateAppLock true called`() = runTest {
        val intent = intentWithExtras(appLock = true)

        TestHooks.applyIfPresent(intent, settings, this)

        verify { settings.updateAppLock(true) }
    }

    // ─── Test 6: biometric-enabled=true delegates correctly ───────────────────

    @Test
    fun `Given biometric-enabled=true when applyIfPresent then updateBiometric true canEnable true called`() = runTest {
        val intent = intentWithExtras(biometric = true)

        TestHooks.applyIfPresent(intent, settings, this)

        verify { settings.updateBiometric(enabled = true, canEnable = true) }
    }

    // ─── Test 7: reset-state=true delegates correctly ─────────────────────────

    @Test
    fun `Given reset-state=true when applyIfPresent then resetFailedPinAttempts called`() = runTest {
        val intent = intentWithExtras(resetState = true)

        TestHooks.applyIfPresent(intent, settings, this)

        verify { settings.resetFailedPinAttempts() }
    }

    // ─── Test 8: Intent with null extras → no settings touched ────────────────

    @Test
    fun `Given intent with no extras when applyIfPresent then no settings methods called`() = runTest {
        val intent = mockk<Intent> {
            every { extras } returns null
        }

        TestHooks.applyIfPresent(intent, settings, this)
        advanceUntilIdle()

        coVerify(exactly = 0) { settings.setPin(any()) }
        verify(exactly = 0) { settings.updateAppLock(any()) }
        verify(exactly = 0) { settings.updateBiometric(any(), any()) }
        verify(exactly = 0) { settings.resetFailedPinAttempts() }
    }

    // ─── Test 9: Null intent → no settings touched, no exception ──────────────

    @Test
    fun `Given null intent when applyIfPresent then no settings methods called and no exception`() = runTest {
        // Must not throw — graceful no-op when intent is null
        TestHooks.applyIfPresent(null, settings, this)
        advanceUntilIdle()

        coVerify(exactly = 0) { settings.setPin(any()) }
        verify(exactly = 0) { settings.updateAppLock(any()) }
        verify(exactly = 0) { settings.updateBiometric(any(), any()) }
        verify(exactly = 0) { settings.resetFailedPinAttempts() }
    }

    // ─── Test 10: setPin throws → exception does NOT propagate ────────────────

    @Test
    fun `Given setPin throws when applyIfPresent then no exception propagates`() = runTest {
        val intent = intentWithExtras(pin = "1234")
        coEvery { settings.setPin(any()) } throws RuntimeException("Simulated keystore failure")

        // Must not throw — the inner try/catch inside the launched coroutine absorbs it
        TestHooks.applyIfPresent(intent, settings, this)
        advanceUntilIdle()

        // Verify the error path was logged
        assert(loggedMessages.any { it.contains("Test hook", ignoreCase = true) }) {
            "Expected an error log for the thrown exception. Got: $loggedMessages"
        }
    }

    // ─── Test 11: location-enabled=false delegates correctly ─────────────────

    @Test
    fun `Given location-enabled=false then updateLocationEnabled false called`() = runTest {
        val intent = intentWithExtras(locationEnabled = false)

        TestHooks.applyIfPresent(intent, settings, this)

        verify { settings.updateLocationEnabled(false) }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a mocked [Intent] that returns the given extras from [Intent.getStringExtra],
     * [Intent.hasExtra], and [Intent.getBooleanExtra]. Only extras that are explicitly
     * provided will appear present (hasExtra returns false for omitted extras).
     */
    private fun intentWithExtras(
        pin: String? = null,
        appLock: Boolean? = null,
        biometric: Boolean? = null,
        resetState: Boolean? = null,
        locationEnabled: Boolean? = null,
    ): Intent = mockk {
        val fakeBundle = mockk<Bundle>(relaxed = true)
        every { extras } returns fakeBundle

        // test-pin
        every { getStringExtra("test-pin") } returns pin

        // app-lock-enabled
        every { hasExtra("app-lock-enabled") } returns (appLock != null)
        every { getBooleanExtra("app-lock-enabled", false) } returns (appLock ?: false)

        // biometric-enabled
        every { hasExtra("biometric-enabled") } returns (biometric != null)
        every { getBooleanExtra("biometric-enabled", false) } returns (biometric ?: false)

        // reset-state — getBooleanExtra is used directly (no hasExtra guard)
        every { getBooleanExtra("reset-state", false) } returns (resetState ?: false)

        // location-enabled
        every { hasExtra("location-enabled") } returns (locationEnabled != null)
        every { getBooleanExtra("location-enabled", true) } returns (locationEnabled ?: true)
    }
}
