package io.woowtech.odoo.ui.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * Tests for BiometricPromptHelper — availability mapping (5 states), callback dispatch (7 cases).
 *
 * The [promptInfoFactory] parameter is injected with a lambda that returns a mock
 * [BiometricPrompt.PromptInfo] to avoid calling [android.text.TextUtils.isEmpty] which
 * is not available on the JVM unit-test runtime (only available on device/emulator).
 */
class BiometricPromptHelperTest {

    private lateinit var activity: FragmentActivity
    private lateinit var biometricManager: BiometricManager
    private lateinit var executor: Executor
    private lateinit var createdPrompt: BiometricPrompt
    private lateinit var mockPromptInfo: BiometricPrompt.PromptInfo
    private var capturedCallback: BiometricPrompt.AuthenticationCallback? = null

    @BeforeEach
    fun setup() {
        activity = mockk(relaxed = true)
        biometricManager = mockk()
        executor = mockk(relaxed = true)
        createdPrompt = mockk(relaxed = true)
        mockPromptInfo = mockk(relaxed = true)
    }

    private fun makeHelper(): BiometricPromptHelper {
        return BiometricPromptHelper(
            activity = activity,
            executor = executor,
            biometricManager = biometricManager,
            promptFactory = { _, _, callback ->
                capturedCallback = callback
                createdPrompt
            },
            promptInfoFactory = { _, _, _ -> mockPromptInfo },
        )
    }

    // Availability mapping — 5 states

    @Test
    fun `Given BIOMETRIC_SUCCESS when canAuthenticate then returns Available`() {
        every { biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_SUCCESS

        assertEquals(BiometricAvailability.Available, makeHelper().canAuthenticate())
    }

    @Test
    fun `Given BIOMETRIC_ERROR_NONE_ENROLLED when canAuthenticate then returns NoneEnrolled`() {
        every { biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

        assertEquals(BiometricAvailability.NoneEnrolled, makeHelper().canAuthenticate())
    }

    @Test
    fun `Given BIOMETRIC_ERROR_NO_HARDWARE when canAuthenticate then returns NoHardware`() {
        every { biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

        assertEquals(BiometricAvailability.NoHardware, makeHelper().canAuthenticate())
    }

    @Test
    fun `Given BIOMETRIC_ERROR_HW_UNAVAILABLE when canAuthenticate then returns HardwareUnavailable`() {
        every { biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE

        assertEquals(BiometricAvailability.HardwareUnavailable, makeHelper().canAuthenticate())
    }

    @Test
    fun `Given BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED when canAuthenticate then returns SecurityUpdateRequired`() {
        every { biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED

        assertEquals(BiometricAvailability.SecurityUpdateRequired, makeHelper().canAuthenticate())
    }

    // Callback dispatch — 7 cases

    private fun setupPromptAndCapture(): BiometricPromptHelper {
        every { biometricManager.canAuthenticate(any()) } returns BiometricManager.BIOMETRIC_SUCCESS
        return makeHelper()
    }

    private fun invokePrompt(
        helper: BiometricPromptHelper,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit = {},
        onFallbackToPin: () -> Unit = {},
        onPermanentLockout: () -> Unit = {},
        onError: (String) -> Unit = {},
        onFailed: () -> Unit = {},
    ) {
        helper.prompt(
            title = "T",
            subtitle = "S",
            negativeText = "N",
            onSuccess = onSuccess,
            onFallbackToPin = onFallbackToPin,
            onPermanentLockout = onPermanentLockout,
            onError = onError,
            onFailed = onFailed,
        )
    }

    @Test
    fun `Given successful auth when onAuthenticationSucceeded then calls onSuccess`() {
        val helper = setupPromptAndCapture()
        var called = false
        invokePrompt(helper, onSuccess = { called = true })

        val result = mockk<BiometricPrompt.AuthenticationResult>(relaxed = true)
        capturedCallback?.onAuthenticationSucceeded(result)

        assertTrue(called)
    }

    @Test
    fun `Given ERROR_USER_CANCELED when onAuthenticationError then calls onFallbackToPin`() {
        val helper = setupPromptAndCapture()
        var called = false
        invokePrompt(helper, onFallbackToPin = { called = true })

        capturedCallback?.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "Canceled")

        assertTrue(called)
    }

    @Test
    fun `Given ERROR_NEGATIVE_BUTTON when onAuthenticationError then calls onFallbackToPin`() {
        val helper = setupPromptAndCapture()
        var called = false
        invokePrompt(helper, onFallbackToPin = { called = true })

        capturedCallback?.onAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Use PIN")

        assertTrue(called)
    }

    @Test
    fun `Given ERROR_LOCKOUT when onAuthenticationError then calls onPermanentLockout`() {
        val helper = setupPromptAndCapture()
        var called = false
        invokePrompt(helper, onPermanentLockout = { called = true })

        capturedCallback?.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "Locked")

        assertTrue(called)
    }

    @Test
    fun `Given ERROR_LOCKOUT_PERMANENT when onAuthenticationError then calls onPermanentLockout`() {
        val helper = setupPromptAndCapture()
        var called = false
        invokePrompt(helper, onPermanentLockout = { called = true })

        capturedCallback?.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, "Permanent")

        assertTrue(called)
    }

    @Test
    fun `Given generic error when onAuthenticationError then calls onError with message`() {
        val helper = setupPromptAndCapture()
        var errorMsg: String? = null
        invokePrompt(helper, onError = { errorMsg = it })

        capturedCallback?.onAuthenticationError(99, "HW failure")

        assertEquals("HW failure", errorMsg)
    }

    @Test
    fun `Given failed attempt when onAuthenticationFailed then calls onFailed`() {
        val helper = setupPromptAndCapture()
        var called = false
        invokePrompt(helper, onFailed = { called = true })

        capturedCallback?.onAuthenticationFailed()

        assertTrue(called)
    }
}
