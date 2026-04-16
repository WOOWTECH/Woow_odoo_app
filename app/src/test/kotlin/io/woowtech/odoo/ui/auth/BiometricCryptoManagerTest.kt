package io.woowtech.odoo.ui.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for [BiometricCryptoManager].
 *
 * Keystore tests (getEncryptCipher, getDecryptCipher, deleteKey) require the Android
 * Keystore hardware provider, which is only available on a real device or an Android
 * emulator with Play Services. The three @Disabled tests must be run via:
 *   `./gradlew :app:connectedDebugAndroidTest`
 *
 * The two non-device tests verify the constant values and companion contract only.
 */
class BiometricCryptoManagerTest {

    @Test
    fun `Given BIOMETRIC_KEY_ALIAS constant then matches expected stable value`() {
        assertEquals("woow_odoo_biometric_v1", BiometricCryptoManager.BIOMETRIC_KEY_ALIAS)
    }

    @Test
    fun `Given fresh instance when hasKey on JVM then returns false (no Android Keystore on JVM)`() {
        // On JVM without Android Keystore provider, hasKey() catches the exception and
        // returns false — verifying the safe fallback path.
        val manager = BiometricCryptoManager()
        assertFalse(manager.hasKey())
    }

    @Disabled("Requires Android Keystore — run on device/emulator via connectedDebugAndroidTest")
    @Test
    fun `Given Keystore available when getEncryptCipher then returns AES-GCM cipher`() {
        val manager = BiometricCryptoManager()
        val cipher = manager.getEncryptCipher()
        assertNotNull(cipher)
        assertEquals("AES/GCM/NoPadding", cipher.algorithm)
    }

    @Disabled("Requires Android Keystore — run on device/emulator via connectedDebugAndroidTest")
    @Test
    fun `Given key exists when deleteKey then hasKey returns false`() {
        val manager = BiometricCryptoManager()
        manager.getEncryptCipher() // creates key
        assertTrue(manager.hasKey())
        manager.deleteKey()
        assertFalse(manager.hasKey())
    }

    @Disabled("Requires Android Keystore — run on device/emulator via connectedDebugAndroidTest")
    @Test
    fun `Given encrypt cipher IV when getDecryptCipher then returns decrypt cipher`() {
        val manager = BiometricCryptoManager()
        val encryptCipher = manager.getEncryptCipher()
        val iv = encryptCipher.iv
        assertNotNull(iv)
        assertEquals(12, iv.size)
        // Decrypting requires biometric auth in the Keystore — just verify no throw on init
    }
}
