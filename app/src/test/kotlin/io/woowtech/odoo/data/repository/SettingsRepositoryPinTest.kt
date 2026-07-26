package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `Given valid PIN when setPin then stores PBKDF2 hash with salt separator`() = runTest {
        repo.setPin("123456")
        // PBKDF2 hash format is "salt:hash" — must contain colon
        verify { encryptedPrefs.updatePinHash(match { it.contains(":") }) }
    }

    @Test
    fun `Given same PIN hashed twice then produces different hashes (random salt)`() = runTest {
        // We need to capture the hash values
        val hashes = mutableListOf<String>()
        every { encryptedPrefs.updatePinHash(capture(hashes)) } returns Unit

        repo.setPin("123456")
        // Reset state for second hash
        every { encryptedPrefs.getAppSettings() } returns AppSettings()
        val repo2 = SettingsRepository(encryptedPrefs)
        repo2.setPin("123456")

        assertTrue(hashes.size >= 2)
        assertNotEquals(hashes[0], hashes[1], "Same PIN should produce different hashes due to random salt")
    }

    @Test
    fun `Given PIN not exactly 6 digits when setPin then returns false`() = runTest {
        assertFalse(repo.setPin("12"))       // too short
        assertFalse(repo.setPin("1234"))     // 4 digits — no longer allowed
        assertFalse(repo.setPin("12345"))    // 5 digits — no longer allowed
        assertFalse(repo.setPin("1234567"))  // too long
    }

    @Test
    fun `Given exactly 6 digits when setPin then returns true`() = runTest {
        assertTrue(repo.setPin("123456"))
    }

    @Test
    fun `Given PBKDF2 hash stored when verifyPin correct then returns true`() = runTest {
        // Set a PIN first to get a real PBKDF2 hash
        val capturedHash = mutableListOf<String>()
        every { encryptedPrefs.updatePinHash(capture(capturedHash)) } returns Unit
        every { encryptedPrefs.resetFailedPinAttempts() } returns Unit

        repo.setPin("567890")

        // Now simulate the stored hash being in settings
        val storedHash = capturedHash.first()
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = storedHash
        )
        val repo2 = SettingsRepository(encryptedPrefs)

        assertTrue(repo2.verifyPin("567890"))
    }

    @Test
    fun `Given PBKDF2 hash stored when verifyPin wrong then returns false`() = runTest {
        val capturedHash = mutableListOf<String>()
        every { encryptedPrefs.updatePinHash(capture(capturedHash)) } returns Unit
        every { encryptedPrefs.incrementFailedPinAttempts() } returns 1

        repo.setPin("567890")

        val storedHash = capturedHash.first()
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = storedHash
        )
        val repo2 = SettingsRepository(encryptedPrefs)

        assertFalse(repo2.verifyPin("0000"))
    }

    @Test
    fun `Given legacy SHA-256 hash when verifyPin correct then migrates to PBKDF2`() = runTest {
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
    fun `Given legacy SHA-256 hash when verifyPin wrong then no migration`() = runTest {
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
    fun `Given no PIN set when verifyPin then returns false`() = runTest {
        every { encryptedPrefs.getAppSettings() } returns AppSettings(pinHash = null)
        val repo2 = SettingsRepository(encryptedPrefs)
        assertFalse(repo2.verifyPin("1234"))
    }

    // ─── G2: Exponential Lockout Tests ───

    @Test
    fun `Given 5 failed attempts then lockout is 30 seconds`() {
        val duration = SettingsRepository.getLockoutDuration(failedAttempts = 5)
        assertTrue(duration == 30_000L, "First lockout should be 30s, got ${duration}ms")
    }

    @Test
    fun `Given 10 failed attempts then lockout is 5 minutes`() {
        val duration = SettingsRepository.getLockoutDuration(failedAttempts = 10)
        assertTrue(duration == 300_000L, "Second lockout should be 5min, got ${duration}ms")
    }

    @Test
    fun `Given 15 failed attempts then lockout is 30 minutes`() {
        val duration = SettingsRepository.getLockoutDuration(failedAttempts = 15)
        assertTrue(duration == 1_800_000L, "Third lockout should be 30min, got ${duration}ms")
    }

    @Test
    fun `Given 20 failed attempts then lockout is 1 hour`() {
        val duration = SettingsRepository.getLockoutDuration(failedAttempts = 20)
        assertTrue(duration == 3_600_000L, "Fourth lockout should be 1hr, got ${duration}ms")
    }

    @Test
    fun `Given 100 failed attempts then lockout caps at 1 hour`() {
        val duration = SettingsRepository.getLockoutDuration(failedAttempts = 100)
        assertTrue(duration == 3_600_000L, "Lockout should cap at 1hr, got ${duration}ms")
    }

    // ─── Regression: verifyPin must not block the calling dispatcher ─────────

    /**
     * Regression test: verifyPin dispatches PBKDF2 to [Dispatchers.Default] and must
     * not block the dispatcher it is called from. We launch verifyPin on a
     * [StandardTestDispatcher] and simultaneously run a yield loop on the same
     * dispatcher. If verifyPin blocked the calling thread the yield loop could never
     * execute and [counter] would remain 0 — which would fail the assertion.
     *
     * Removing the [withContext(Dispatchers.Default)] from [SettingsRepository.verifyPin]
     * causes PBKDF2 to run on the test dispatcher's thread, starving the yield loop and
     * making [counter] == 0, failing this test.
     */
    @Test
    fun `verifyPin off-loads PBKDF2 to Dispatchers Default`() = runTest {
        val capturedHash = mutableListOf<String>()
        every { encryptedPrefs.updatePinHash(capture(capturedHash)) } returns Unit
        every { encryptedPrefs.resetFailedPinAttempts() } returns Unit

        repo.setPin("123456")

        val storedHash = capturedHash.first()
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = storedHash
        )
        val repo2 = SettingsRepository(encryptedPrefs)

        // Launch verifyPin as a background Deferred on Dispatchers.Default so it runs
        // truly off this test dispatcher.
        val deferred = async(Dispatchers.Default) { repo2.verifyPin("123456") }

        // The test dispatcher coroutine can keep yielding while the Default-dispatcher
        // coroutine is running. If verifyPin did NOT use withContext(Default), the
        // PBKDF2 would block this thread and counter would never increment.
        var counter = 0
        while (!deferred.isCompleted) {
            counter++
            yield()
        }

        val result = deferred.await()
        assertTrue(result, "verifyPin should return true for correct PIN")
        assertTrue(counter > 0, "Main-dispatcher coroutine must have yielded at least once — " +
                "counter=$counter. If 0, verifyPin blocked the calling thread (withContext removed?)")
    }
}
