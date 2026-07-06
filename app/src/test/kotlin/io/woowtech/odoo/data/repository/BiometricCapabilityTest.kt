package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [SettingsRepository.updateBiometric] with canEnable validation. (M1 fix)
 */
class BiometricCapabilityTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var repo: SettingsRepository

    @BeforeEach
    fun setup() {
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns AppSettings()
        repo = SettingsRepository(encryptedPrefs)
    }

    @Test
    fun `Given canEnable true when updateBiometric true then biometricEnabled becomes true`() {
        repo.updateBiometric(enabled = true, canEnable = true)

        assertTrue(repo.settings.value.biometricEnabled)
        verify { encryptedPrefs.updateBiometric(true) }
    }

    @Test
    fun `Given canEnable false when updateBiometric true then biometricEnabled forced to false`() {
        repo.updateBiometric(enabled = true, canEnable = false)

        assertFalse(repo.settings.value.biometricEnabled)
        verify { encryptedPrefs.updateBiometric(false) }
    }

    @Test
    fun `Given canEnable false when updateBiometric false then biometricEnabled stays false`() {
        repo.updateBiometric(enabled = false, canEnable = false)

        assertFalse(repo.settings.value.biometricEnabled)
        verify { encryptedPrefs.updateBiometric(false) }
    }

    @Test
    fun `Given no canEnable argument when updateBiometric true then defaults to enabled true`() {
        // Default canEnable = true so the setting should be applied as requested
        repo.updateBiometric(enabled = true)

        assertTrue(repo.settings.value.biometricEnabled)
    }

    @Test
    fun `Given biometric enabled when updateBiometric with canEnable false then disables it`() {
        // Start with biometric enabled
        every { encryptedPrefs.getAppSettings() } returns AppSettings(biometricEnabled = true)
        val repoWithBiometric = SettingsRepository(encryptedPrefs)
        assertTrue(repoWithBiometric.settings.value.biometricEnabled)

        repoWithBiometric.updateBiometric(enabled = true, canEnable = false)

        assertFalse(repoWithBiometric.settings.value.biometricEnabled)
    }
}
