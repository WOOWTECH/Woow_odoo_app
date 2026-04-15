package io.woowtech.odoo.ui.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Unit tests for [BiometricPromptHelper]. [BiometricManager] is injected via the
 * constructor so we don't need to stub the `BiometricManager.from(context)` static,
 * and [BiometricPrompt] is replaced via the [promptFactory] seam so we can capture the
 * [BiometricPrompt.AuthenticationCallback] and invoke it synchronously.
 */
class BiometricPromptHelperTest {

    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val executor = Executor { it.run() }
    private val biometricManager = mockk<BiometricManager>(relaxed = true)

    private fun helperReturning(status: Int): BiometricPromptHelper {
        every {
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } returns status
        return BiometricPromptHelper(activity, executor, biometricManager)
    }

    // ---------- canAuthenticate mapping ----------

    @Test
    fun `Given canAuthenticate returns BIOMETRIC_SUCCESS then availability=Available`() {
        val helper = helperReturning(BiometricManager.BIOMETRIC_SUCCESS)
        assertEquals(BiometricAvailability.Available, helper.canAuthenticate())
    }

    @Test
    fun `Given canAuthenticate returns BIOMETRIC_ERROR_NONE_ENROLLED then availability=NoneEnrolled`() {
        val helper = helperReturning(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        assertEquals(BiometricAvailability.NoneEnrolled, helper.canAuthenticate())
    }

    @Test
    fun `Given canAuthenticate returns BIOMETRIC_ERROR_NO_HARDWARE then availability=NoHardware`() {
        val helper = helperReturning(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        assertEquals(BiometricAvailability.NoHardware, helper.canAuthenticate())
    }

    @Test
    fun `Given canAuthenticate returns BIOMETRIC_ERROR_HW_UNAVAILABLE then availability=HardwareUnavailable`() {
        val helper = helperReturning(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        assertEquals(BiometricAvailability.HardwareUnavailable, helper.canAuthenticate())
    }

    @Test
    fun `Given canAuthenticate returns BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED then availability=SecurityUpdateRequired`() {
        val helper = helperReturning(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED)
        assertEquals(BiometricAvailability.SecurityUpdateRequired, helper.canAuthenticate())
    }

    // ---------- prompt callback dispatch ----------

    private data class Invocations(
        var success: Int = 0,
        var fallback: Int = 0,
        var permanentLockout: Int = 0,
        var error: String? = null,
        var failed: Int = 0,
    )

    private fun runPromptCapturingCallback(): Pair<CapturingSlot<BiometricPrompt.AuthenticationCallback>, Invocations> {
        val callbackSlot = slot<BiometricPrompt.AuthenticationCallback>()
        val fakePrompt = mockk<BiometricPrompt>(relaxed = true)
        every { fakePrompt.authenticate(any()) } just runs

        val helper = BiometricPromptHelper(
            activity = activity,
            executor = executor,
            biometricManager = biometricManager,
            promptFactory = { _, _, cb ->
                callbackSlot.captured = cb
                fakePrompt
            },
        )

        val invocations = Invocations()
        helper.prompt(
            title = "t",
            subtitle = "s",
            negativeText = "n",
            onSuccess = { invocations.success++ },
            onFallbackToPin = { invocations.fallback++ },
            onPermanentLockout = { invocations.permanentLockout++ },
            onError = { invocations.error = it },
            onFailed = { invocations.failed++ },
        )
        verify { fakePrompt.authenticate(any()) }
        assertTrue("callback must be captured", callbackSlot.isCaptured)
        return callbackSlot to invocations
    }

    @Test
    fun `Given prompt invoked when onAuthenticationSucceeded then onSuccess fires`() {
        val (cb, inv) = runPromptCapturingCallback()
        cb.captured.onAuthenticationSucceeded(mockk(relaxed = true))
        assertEquals(1, inv.success)
    }

    @Test
    fun `Given prompt invoked when onAuthenticationError ERROR_NEGATIVE_BUTTON then onFallbackToPin fires`() {
        val (cb, inv) = runPromptCapturingCallback()
        cb.captured.onAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "cancel")
        assertEquals(1, inv.fallback)
    }

    @Test
    fun `Given prompt invoked when onAuthenticationError ERROR_USER_CANCELED then onFallbackToPin fires`() {
        val (cb, inv) = runPromptCapturingCallback()
        cb.captured.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "cancel")
        assertEquals(1, inv.fallback)
    }

    @Test
    fun `Given prompt invoked when onAuthenticationError ERROR_LOCKOUT_PERMANENT then onPermanentLockout fires`() {
        val (cb, inv) = runPromptCapturingCallback()
        cb.captured.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, "locked")
        assertEquals(1, inv.permanentLockout)
    }

    @Test
    fun `Given prompt invoked when onAuthenticationError ERROR_LOCKOUT then onPermanentLockout fires`() {
        val (cb, inv) = runPromptCapturingCallback()
        cb.captured.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "locked")
        assertEquals(1, inv.permanentLockout)
    }

    @Test
    fun `Given prompt invoked when onAuthenticationError unknown code then onError fires with message`() {
        val (cb, inv) = runPromptCapturingCallback()
        cb.captured.onAuthenticationError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "hw gone")
        assertEquals("hw gone", inv.error)
    }

    @Test
    fun `Given prompt invoked when onAuthenticationFailed then onFailed fires`() {
        val (cb, inv) = runPromptCapturingCallback()
        cb.captured.onAuthenticationFailed()
        assertEquals(1, inv.failed)
    }
}
