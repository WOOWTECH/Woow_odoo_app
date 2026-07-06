package io.woowtech.odoo.ui.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.repository.SettingsRepository
import io.woowtech.odoo.domain.model.AppSettings
import io.woowtech.odoo.data.local.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the Reduce Motion preference (H1 fix).
 *
 * Verifies:
 * 1. reduceMotion=true → settings state reflects it (snap() animation spec branch)
 * 2. reduceMotion=false → settings state reflects it (tween(300) animation spec branch)
 * 3. updateReduceMotion persists through SettingsRepository
 *
 * Note: The actual animation spec selection (snap() vs tween(300)) is a pure Compose
 * decision inside BiometricScreen/PinScreen that cannot be tested without UI rendering.
 * These tests verify the data contract — that the flag is correctly set, read, and
 * that the SettingsRepository dispatches it properly. The UI layer is trusted to read
 * the flag and apply the correct animation spec (covered by visual inspection on device).
 */
class AnimationReduceMotionTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var repo: SettingsRepository

    @BeforeEach
    fun setup() {
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns AppSettings(reduceMotion = false)
        repo = SettingsRepository(encryptedPrefs)
    }

    @Test
    fun `Given reduceMotion false by default when settings read then reduceMotion is false`() {
        assertFalse(repo.settings.value.reduceMotion)
    }

    @Test
    fun `Given reduceMotion false when updateReduceMotion true then settings reflect true`() {
        repo.updateReduceMotion(enabled = true)

        assertTrue(repo.settings.value.reduceMotion)
    }

    @Test
    fun `Given reduceMotion true when updateReduceMotion false then settings reflect false`() {
        every { encryptedPrefs.getAppSettings() } returns AppSettings(reduceMotion = true)
        val repoWithMotion = SettingsRepository(encryptedPrefs)
        assertTrue(repoWithMotion.settings.value.reduceMotion)

        repoWithMotion.updateReduceMotion(enabled = false)

        assertFalse(repoWithMotion.settings.value.reduceMotion)
    }

    @Test
    fun `Given updateReduceMotion true when called then persists to EncryptedPrefs`() {
        repo.updateReduceMotion(enabled = true)

        verify {
            encryptedPrefs.saveAppSettings(match { it.reduceMotion })
        }
    }

    @Test
    fun `Given updateReduceMotion false when called then persists false to EncryptedPrefs`() {
        every { encryptedPrefs.getAppSettings() } returns AppSettings(reduceMotion = true)
        val repoWithMotion = SettingsRepository(encryptedPrefs)

        repoWithMotion.updateReduceMotion(enabled = false)

        verify {
            encryptedPrefs.saveAppSettings(match { !it.reduceMotion })
        }
    }

    @Test
    fun `Given reduceMotion flag when BiometricScreen reads it then snap spec should be used`() {
        // This test documents the expected contract: when reduceMotion=true the animation
        // spec in BiometricScreen is snap(); when false it is tween(300). This cannot be
        // asserted via JVM tests — it is a Compose animation spec, verified visually on
        // device. The contract is recorded here so future regressions are noticed.
        repo.updateReduceMotion(enabled = true)
        assertTrue(repo.settings.value.reduceMotion, "BiometricScreen should use snap() when true")

        repo.updateReduceMotion(enabled = false)
        assertFalse(repo.settings.value.reduceMotion, "BiometricScreen should use tween(300) when false")
    }
}
