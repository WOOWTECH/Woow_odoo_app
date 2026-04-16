package io.woowtech.odoo.ui.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [BiometricPromptHelper.cancelPendingAuthentication].
 * Verifies: cancel invokes cancelAuthentication, no-op when idle, no double-cancel.
 *
 * The [promptInfoFactory] parameter is injected with a mock to avoid calling
 * [android.text.TextUtils.isEmpty] on the JVM unit-test runtime (not available without
 * Robolectric or device).
 */
class BiometricPromptHelperCancelTest {

    private lateinit var activity: FragmentActivity
    private lateinit var biometricManager: BiometricManager
    private lateinit var capturedPrompt: BiometricPrompt
    private lateinit var mockPromptInfo: BiometricPrompt.PromptInfo

    @BeforeEach
    fun setup() {
        activity = mockk(relaxed = true)
        biometricManager = mockk()
        every { biometricManager.canAuthenticate(any()) } returns BiometricManager.BIOMETRIC_SUCCESS
        capturedPrompt = mockk(relaxed = true)
        mockPromptInfo = mockk(relaxed = true)
    }

    private fun makeHelperAndPrompt(): BiometricPromptHelper {
        return BiometricPromptHelper(
            activity = activity,
            executor = mockk(relaxed = true),
            biometricManager = biometricManager,
            promptFactory = { _, _, _ -> capturedPrompt },
            promptInfoFactory = { _, _, _ -> mockPromptInfo },
        )
    }

    @Test
    fun `Given active prompt when cancelPendingAuthentication then calls cancelAuthentication`() {
        val helper = makeHelperAndPrompt()
        helper.prompt(
            title = "T",
            subtitle = "S",
            negativeText = "N",
            onSuccess = {},
            onFallbackToPin = {},
            onPermanentLockout = {},
            onError = {},
            onFailed = {},
        )

        helper.cancelPendingAuthentication()

        verify(exactly = 1) { capturedPrompt.cancelAuthentication() }
    }

    @Test
    fun `Given no active prompt when cancelPendingAuthentication then no-op`() {
        val helper = makeHelperAndPrompt()

        // No prompt was started
        helper.cancelPendingAuthentication()

        // cancelAuthentication should never be called because no prompt was created
        verify(exactly = 0) { capturedPrompt.cancelAuthentication() }
    }

    @Test
    fun `Given cancelled prompt when cancelPendingAuthentication again then no double-cancel`() {
        val helper = makeHelperAndPrompt()
        helper.prompt(
            title = "T",
            subtitle = "S",
            negativeText = "N",
            onSuccess = {},
            onFallbackToPin = {},
            onPermanentLockout = {},
            onError = {},
            onFailed = {},
        )

        helper.cancelPendingAuthentication()
        helper.cancelPendingAuthentication() // second call should be no-op

        // cancelAuthentication should be called exactly once despite two cancel calls
        verify(exactly = 1) { capturedPrompt.cancelAuthentication() }
    }
}
