package io.woowtech.odoo.ui.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Unit tests for the [BiometricPromptHelper.cancelPendingAuthentication] method added as
 * part of the L4 fix (stale callback after rotation).
 *
 * Verifies that:
 * - cancelPendingAuthentication() calls [BiometricPrompt.cancelAuthentication] on the
 *   most recently created prompt.
 * - Calling cancelPendingAuthentication() when no prompt is active is a safe no-op.
 * - After cancellation the internal reference is cleared so a second call does not
 *   double-cancel on a stale instance.
 */
class BiometricPromptHelperCancelTest {

    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val executor = Executor { it.run() }
    private val biometricManager = mockk<BiometricManager>(relaxed = true)

    private fun buildHelperWithFakePrompt(): Pair<BiometricPromptHelper, BiometricPrompt> {
        val fakePrompt = mockk<BiometricPrompt>(relaxed = true)
        every { fakePrompt.authenticate(any()) } just runs
        every { fakePrompt.cancelAuthentication() } just runs

        val helper = BiometricPromptHelper(
            activity = activity,
            executor = executor,
            biometricManager = biometricManager,
            promptFactory = { _, _, _ -> fakePrompt },
        )
        return helper to fakePrompt
    }

    @Test
    fun `Given prompt is active when cancelPendingAuthentication called then cancelAuthentication is invoked`() {
        val (helper, fakePrompt) = buildHelperWithFakePrompt()

        helper.prompt(
            title = "t",
            subtitle = "s",
            negativeText = "n",
            onSuccess = {},
            onFallbackToPin = {},
            onPermanentLockout = {},
            onError = {},
            onFailed = {},
        )

        helper.cancelPendingAuthentication()

        verify(exactly = 1) { fakePrompt.cancelAuthentication() }
    }

    @Test
    fun `Given no active prompt when cancelPendingAuthentication called then no exception thrown`() {
        val (helper, fakePrompt) = buildHelperWithFakePrompt()

        // No prompt() call — cancelPendingAuthentication must be a safe no-op.
        helper.cancelPendingAuthentication()

        verify(exactly = 0) { fakePrompt.cancelAuthentication() }
    }

    @Test
    fun `Given active prompt cancelled when cancelPendingAuthentication called again then cancelAuthentication invoked only once`() {
        val (helper, fakePrompt) = buildHelperWithFakePrompt()

        helper.prompt(
            title = "t",
            subtitle = "s",
            negativeText = "n",
            onSuccess = {},
            onFallbackToPin = {},
            onPermanentLockout = {},
            onError = {},
            onFailed = {},
        )

        // First cancel — reference is cleared internally.
        helper.cancelPendingAuthentication()
        // Second cancel — reference is null, must not double-cancel.
        helper.cancelPendingAuthentication()

        // cancelAuthentication must only be called once, for the original prompt instance.
        verify(exactly = 1) { fakePrompt.cancelAuthentication() }
    }
}
