package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the M1 fix: biometric capability is re-validated before the setting
 * is persisted as true.
 *
 * [SettingsRepository.updateBiometric] accepts a [canEnable] parameter (default true for
 * backwards compatibility). When [enabled]=true and [canEnable]=false the effective stored
 * value must be false.
 */
class BiometricCapabilityTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns AppSettings(biometricEnabled = false)
        repository = SettingsRepository(encryptedPrefs)
    }

    @Test
    fun `Given biometric capability available when updateBiometric true with canEnable true then persists true`() {
        repository.updateBiometric(enabled = true, canEnable = true)

        verify { encryptedPrefs.updateBiometric(true) }
        assertTrue(
            "biometricEnabled must be true when capability is available",
            repository.settings.value.biometricEnabled
        )
    }

    @Test
    fun `Given biometric capability unavailable when updateBiometric true with canEnable false then persists false`() {
        repository.updateBiometric(enabled = true, canEnable = false)

        // The repository must clamp the value to false and persist false, not true.
        verify { encryptedPrefs.updateBiometric(false) }
        assertFalse(
            "biometricEnabled must be false when capability is unavailable, even if caller requested true",
            repository.settings.value.biometricEnabled
        )
    }

    @Test
    fun `Given biometric capability unavailable when updateBiometric false with canEnable false then persists false`() {
        // Turning off always works regardless of capability.
        repository.updateBiometric(enabled = false, canEnable = false)

        verify { encryptedPrefs.updateBiometric(false) }
        assertFalse(
            "biometricEnabled must be false when disabled",
            repository.settings.value.biometricEnabled
        )
    }

    @Test
    fun `Given biometric was enabled when updateBiometric called with canEnable false then in-memory state updated to false`() = runTest {
        every { encryptedPrefs.getAppSettings() } returns AppSettings(biometricEnabled = true)
        val repo = SettingsRepository(encryptedPrefs)

        repo.updateBiometric(enabled = true, canEnable = false)

        assertFalse(
            "In-memory biometricEnabled must become false when canEnable=false",
            repo.settings.first().biometricEnabled
        )
    }

    @Test
    fun `Given default canEnable when updateBiometric true called then persists true (backwards compat)`() {
        // Verify the default value of canEnable=true preserves old call-site behaviour.
        repository.updateBiometric(enabled = true)

        verify { encryptedPrefs.updateBiometric(true) }
        assertTrue(
            "Default canEnable=true must not block a true persist",
            repository.settings.value.biometricEnabled
        )
    }
}
