package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SettingsRepositoryPinTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var repo: SettingsRepository

    @BeforeEach
    fun setup() {
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns AppSettings()
        repo = SettingsRepository(encryptedPrefs)
    }

    @Test
    fun `Given valid PIN when setPin then stores PBKDF2 hash with salt separator`() {
        repo.setPin("1234")
        // PBKDF2 hash format is "salt:hash" — must contain colon
        verify { encryptedPrefs.updatePinHash(match { it.contains(":") }) }
    }

    @Test
    fun `Given same PIN hashed twice then produces different hashes (random salt)`() {
        // We need to capture the hash values
        val hashes = mutableListOf<String>()
        every { encryptedPrefs.updatePinHash(capture(hashes)) } returns Unit

        repo.setPin("1234")
        // Reset state for second hash
        every { encryptedPrefs.getAppSettings() } returns AppSettings()
        val repo2 = SettingsRepository(encryptedPrefs)
        repo2.setPin("1234")

        assertTrue(hashes.size >= 2)
        assertNotEquals(hashes[0], hashes[1], "Same PIN should produce different hashes due to random salt")
    }

    @Test
    fun `Given PIN too short when setPin then returns false`() {
        assertFalse(repo.setPin("12"))  // < 4 digits
    }

    @Test
    fun `Given PIN too long when setPin then returns false`() {
        assertFalse(repo.setPin("1234567"))  // > 6 digits
    }

    @Test
    fun `Given PBKDF2 hash stored when verifyPin correct then returns true`() {
        // Set a PIN first to get a real PBKDF2 hash
        val capturedHash = mutableListOf<String>()
        every { encryptedPrefs.updatePinHash(capture(capturedHash)) } returns Unit
        every { encryptedPrefs.resetFailedPinAttempts() } returns Unit

        repo.setPin("5678")

        // Now simulate the stored hash being in settings
        val storedHash = capturedHash.first()
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = storedHash
        )
        val repo2 = SettingsRepository(encryptedPrefs)

        assertTrue(repo2.verifyPin("5678"))
    }

    @Test
    fun `Given PBKDF2 hash stored when verifyPin wrong then returns false`() {
        val capturedHash = mutableListOf<String>()
        every { encryptedPrefs.updatePinHash(capture(capturedHash)) } returns Unit
        every { encryptedPrefs.incrementFailedPinAttempts() } returns 1

        repo.setPin("5678")

        val storedHash = capturedHash.first()
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = storedHash
        )
        val repo2 = SettingsRepository(encryptedPrefs)

        assertFalse(repo2.verifyPin("0000"))
    }

    @Test
    fun `Given legacy SHA-256 hash when verifyPin correct then migrates to PBKDF2`() {
        // Legacy SHA-256 hash of "1234" (no colon = old format)
        val legacySha256 = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"

        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = legacySha256
        )
        every { encryptedPrefs.resetFailedPinAttempts() } returns Unit

        val repo2 = SettingsRepository(encryptedPrefs)
        assertTrue(repo2.verifyPin("1234"))

        // Should have migrated — new hash has colon (PBKDF2 format)
        verify { encryptedPrefs.updatePinHash(match { it.contains(":") }) }
    }

    @Test
    fun `Given legacy SHA-256 hash when verifyPin wrong then no migration`() {
        val legacySha256 = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"

        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = legacySha256
        )
        every { encryptedPrefs.incrementFailedPinAttempts() } returns 1

        val repo2 = SettingsRepository(encryptedPrefs)
        assertFalse(repo2.verifyPin("0000"))

        // Should NOT migrate on wrong PIN
        verify(exactly = 0) { encryptedPrefs.updatePinHash(any()) }
    }

    @Test
    fun `Given no PIN set when verifyPin then returns false`() {
        every { encryptedPrefs.getAppSettings() } returns AppSettings(pinHash = null)
        val repo2 = SettingsRepository(encryptedPrefs)
        assertFalse(repo2.verifyPin("1234"))
    }
}
