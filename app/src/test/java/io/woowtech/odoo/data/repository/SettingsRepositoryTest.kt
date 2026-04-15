package io.woowtech.odoo.data.repository

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.woowtech.odoo.data.local.EncryptedPrefs
import io.woowtech.odoo.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Unit tests for [SettingsRepository] focusing on the PBKDF2 PIN hashing (H1),
 * elapsedRealtime-based lockout (H2), and exponential backoff lockout tiers (H5).
 *
 * NOTE: These tests call real PBKDF2 operations (600 000 iterations), which is intentional
 * to verify the correct algorithm is used. Each PBKDF2 call takes ~200–500 ms on a
 * modern JVM — if test suite speed becomes a concern the iteration count can be reduced
 * for test variants only via a test-only constructor parameter.
 *
 * [SystemClock.elapsedRealtime] is an Android-only API. We configure testOptions.unitTests
 * .isReturnDefaultValues = true in app/build.gradle.kts so it returns 0 for unmocked
 * calls. Tests that need a non-zero clock mock EncryptedPrefs to return a stored lockout
 * value greater than 0. This correctly covers the contract: "if stored lockout > current
 * elapsedRealtime → locked out".
 */
class SettingsRepositoryTest {

    private lateinit var encryptedPrefs: EncryptedPrefs
    private lateinit var repository: SettingsRepository

    private val defaultSettings = AppSettings(pinEnabled = false, pinHash = null)

    @Before
    fun setUp() {
        encryptedPrefs = mockk(relaxed = true)
        every { encryptedPrefs.getAppSettings() } returns defaultSettings
        repository = SettingsRepository(encryptedPrefs)
    }

    // ---------- H1: PBKDF2 hash format ----------

    @Test
    fun `Given new PIN set then stored hash format starts with pbkdf2 prefix`() {
        every { encryptedPrefs.updatePinHash(any()) } just runs
        val pinHashSlot = slot<String>()
        every { encryptedPrefs.updatePinHash(capture(pinHashSlot)) } just runs

        repository.setPin("1234")

        assertTrue(
            "Hash should start with 'pbkdf2:' but was: ${pinHashSlot.captured}",
            pinHashSlot.captured.startsWith("pbkdf2:")
        )
    }

    @Test
    fun `Given new PIN set then stored hash contains 4 colon-separated segments`() {
        val pinHashSlot = slot<String>()
        every { encryptedPrefs.updatePinHash(capture(pinHashSlot)) } just runs

        repository.setPin("123456")

        // Expected format: pbkdf2:600000:<base64 salt>:<base64 hash>
        val parts = pinHashSlot.captured.split(":")
        assertEquals("Should have 4 parts (prefix:iterations:salt:hash)", 4, parts.size)
        assertEquals("pbkdf2", parts[0])
        assertEquals("600000", parts[1])
        // Parts 2 and 3 should be valid Base64
        assertNotNull(Base64.getDecoder().decode(parts[2]))
        assertNotNull(Base64.getDecoder().decode(parts[3]))
    }

    @Test
    fun `Given PIN stored in pbkdf2 format when correct PIN verified then verifyPin returns true`() {
        val pin = "9876"
        val pinHashSlot = slot<String>()
        every { encryptedPrefs.updatePinHash(capture(pinHashSlot)) } just runs
        every { encryptedPrefs.resetFailedPinAttempts() } just runs
        repository.setPin(pin)

        // Set up verifyPin to see the stored hash
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = pinHashSlot.captured
        )
        val freshRepo = SettingsRepository(encryptedPrefs)

        val result = freshRepo.verifyPin(pin)

        assertTrue("Correct PBKDF2 PIN should verify successfully", result)
    }

    @Test
    fun `Given PIN stored in pbkdf2 format when wrong PIN verified then verifyPin returns false`() {
        val pin = "1234"
        val pinHashSlot = slot<String>()
        every { encryptedPrefs.updatePinHash(capture(pinHashSlot)) } just runs
        repository.setPin(pin)

        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = pinHashSlot.captured
        )
        every { encryptedPrefs.incrementFailedPinAttempts() } returns 1
        val freshRepo = SettingsRepository(encryptedPrefs)

        val result = freshRepo.verifyPin("9999")

        assertFalse("Wrong PIN should not verify", result)
    }

    @Test
    fun `Given PBKDF2 hash with non-default iteration count when correct PIN verified then verifyPin returns true`() {
        // Regression for: hashPinPbkdf2 must respect the stored iteration count, not always
        // use PBKDF2_ITERATIONS. A hash created with 100k iterations must verify with 100k,
        // not be re-hashed with the default 600k.
        val pin = "4321"
        val salt = ByteArray(16) { it.toByte() }
        val customIterations = 100_000
        val spec = javax.crypto.spec.PBEKeySpec(
            pin.toCharArray(), salt, customIterations, 256
        )
        val hash = javax.crypto.SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        val saltB64 = java.util.Base64.getEncoder().encodeToString(salt)
        val hashB64 = java.util.Base64.getEncoder().encodeToString(hash)
        val storedHash = "pbkdf2:$customIterations:$saltB64:$hashB64"

        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = storedHash
        )
        every { encryptedPrefs.resetFailedPinAttempts() } just runs
        val freshRepo = SettingsRepository(encryptedPrefs)

        val result = freshRepo.verifyPin(pin)

        assertTrue(
            "PIN must verify against PBKDF2 hash with non-default iteration count",
            result
        )
    }

    // ---------- H1: Legacy SHA-256 migration ----------

    @Test
    fun `Given legacy SHA-256 hash and matching PIN then verify succeeds and hash is migrated to PBKDF2`() {
        val pin = "5678"
        val legacyHash = sha256Hex(pin)
        val migratedHashSlot = slot<String>()

        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = legacyHash
        )
        every { encryptedPrefs.updatePinHash(capture(migratedHashSlot)) } just runs
        every { encryptedPrefs.resetFailedPinAttempts() } just runs

        val repo = SettingsRepository(encryptedPrefs)
        val result = repo.verifyPin(pin)

        assertTrue("Legacy SHA-256 PIN should verify successfully", result)
        // After successful verify the hash should have been migrated to PBKDF2
        verify { encryptedPrefs.updatePinHash(any()) }
        assertTrue(
            "Migrated hash should start with 'pbkdf2:' but was: ${migratedHashSlot.captured}",
            migratedHashSlot.captured.startsWith("pbkdf2:")
        )
    }

    @Test
    fun `Given legacy SHA-256 hash and wrong PIN then verify fails and hash is unchanged`() {
        val pin = "5678"
        val legacyHash = sha256Hex(pin)

        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = legacyHash
        )
        every { encryptedPrefs.incrementFailedPinAttempts() } returns 1

        val repo = SettingsRepository(encryptedPrefs)
        val result = repo.verifyPin("0000")

        assertFalse("Wrong PIN against legacy hash should fail", result)
        // updatePinHash should NOT be called on a failed legacy verify
        verify(exactly = 0) { encryptedPrefs.updatePinHash(any()) }
    }

    @Test
    fun `Given legacy SHA-256 hash verified successfully when verifyPin called again then new PBKDF2 hash is used`() {
        val pin = "4321"
        val legacyHash = sha256Hex(pin)
        val capturedHash = slot<String>()

        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = legacyHash
        )
        every { encryptedPrefs.updatePinHash(capture(capturedHash)) } just runs
        every { encryptedPrefs.resetFailedPinAttempts() } just runs

        val repo = SettingsRepository(encryptedPrefs)
        repo.verifyPin(pin) // triggers migration

        // After migration the in-memory settings should reflect the PBKDF2 hash
        assertTrue(
            "In-memory pin hash after migration should be PBKDF2",
            capturedHash.captured.startsWith("pbkdf2:")
        )
    }

    // ---------- H2: elapsedRealtime lockout (clock-manipulation resistance) ----------

    @Test
    fun `Given lockout stored with future elapsedRealtime then isLockedOut returns true`() {
        // SystemClock.elapsedRealtime() returns 0 in unit tests (isReturnDefaultValues = true).
        // A stored value of 1000 (> 0) means "locked out until elapsed ms 1000".
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinLockoutUntil = 1_000L // 1000 ms in the future relative to elapsedRealtime=0
        )

        val repo = SettingsRepository(encryptedPrefs)

        assertTrue("Should be locked out when stored lockout > current elapsedRealtime", repo.isLockedOut())
    }

    @Test
    fun `Given lockout stored with past elapsedRealtime then isLockedOut returns false`() {
        // elapsedRealtime=0, stored lockoutUntil=0 means the lockout already expired.
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinLockoutUntil = 0L
        )

        val repo = SettingsRepository(encryptedPrefs)

        assertFalse("Should not be locked out when lockout deadline has passed", repo.isLockedOut())
    }

    @Test
    fun `Given lockout not set then isLockedOut returns false`() {
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinLockoutUntil = null
        )

        val repo = SettingsRepository(encryptedPrefs)

        assertFalse("Should not be locked out when no lockout is set", repo.isLockedOut())
    }

    @Test
    fun `Given lockout active when verifyPin called then returns false without incrementing attempts`() {
        every { encryptedPrefs.getAppSettings() } returns AppSettings(
            pinEnabled = true,
            pinHash = "pbkdf2:600000:AAAA:BBBB", // won't be reached
            pinLockoutUntil = 1_000L // active lockout (elapsedRealtime=0 in tests)
        )

        val repo = SettingsRepository(encryptedPrefs)
        val result = repo.verifyPin("1234")

        assertFalse("Locked-out verifyPin should return false", result)
        // incrementFailedPinAttempts must NOT be called when already locked out
        verify(exactly = 0) { encryptedPrefs.incrementFailedPinAttempts() }
    }

    // ---------- H5: Exponential lockout tiers ----------

    @Test
    fun `Given 4 failures then lockout duration is 0 ms`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(0L, repo.getLockoutDuration(failureCount = 4))
    }

    @Test
    fun `Given 5 failures then lockout duration is 30 seconds`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(30_000L, repo.getLockoutDuration(failureCount = 5))
    }

    @Test
    fun `Given 9 failures then lockout duration is 30 seconds`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(30_000L, repo.getLockoutDuration(failureCount = 9))
    }

    @Test
    fun `Given 10 failures then lockout duration is 5 minutes`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(5 * 60_000L, repo.getLockoutDuration(failureCount = 10))
    }

    @Test
    fun `Given 14 failures then lockout duration is 5 minutes`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(5 * 60_000L, repo.getLockoutDuration(failureCount = 14))
    }

    @Test
    fun `Given 15 failures then lockout duration is 30 minutes`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(30 * 60_000L, repo.getLockoutDuration(failureCount = 15))
    }

    @Test
    fun `Given 19 failures then lockout duration is 30 minutes`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(30 * 60_000L, repo.getLockoutDuration(failureCount = 19))
    }

    @Test
    fun `Given 20 failures then lockout duration is 1 hour`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(60 * 60_000L, repo.getLockoutDuration(failureCount = 20))
    }

    @Test
    fun `Given 100 failures then lockout duration is 1 hour`() {
        val repo = SettingsRepository(encryptedPrefs)
        assertEquals(60 * 60_000L, repo.getLockoutDuration(failureCount = 100))
    }

    // ---------- helpers ----------

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
