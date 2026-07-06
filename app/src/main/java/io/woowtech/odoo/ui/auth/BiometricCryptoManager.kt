package io.woowtech.odoo.ui.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the Android Keystore key used to bind biometric authentication to a
 * cryptographic operation, preventing callback-spoofing attacks (OWASP MASVS-AUTH-2).
 *
 * The key alias [BIOMETRIC_KEY_ALIAS] is stored in the AndroidKeyStore provider and is
 * configured with [KeyProperties.PURPOSE_ENCRYPT] and [KeyProperties.PURPOSE_DECRYPT].
 * `setUserAuthenticationRequired(true)` ensures the key can only be used immediately after
 * a strong biometric auth event — not from a replay of a previous success callback.
 *
 * On API 30+ the key uses `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` which
 * requires a fresh biometric each time (timeout=0 means "only right now"). On API 29 the
 * equivalent is `setUserAuthenticationValidityDurationSeconds(-1)`.
 *
 * Usage pattern:
 * 1. Call [getEncryptCipher] → pass the resulting [Cipher] inside a [androidx.biometric.BiometricPrompt.CryptoObject]
 * 2. On [androidx.biometric.BiometricPrompt.AuthenticationResult], verify `result.cryptoObject?.cipher != null`
 * 3. On first-time enrollment: use the cipher to encrypt a random proof token and store it
 *    via EncryptedPrefs (the IV must also be stored so decryption can be performed later)
 * 4. On subsequent unlocks: call [getDecryptCipher] with the stored IV → pass to CryptoObject
 * 5. After biometric success decrypt the proof token; a failed decryption means the key was
 *    tampered with and should be treated as an authentication failure
 *
 * If [KeyPermanentlyInvalidatedException] is thrown (e.g. new biometric enrolled or all
 * biometrics removed), the key is deleted so the user must re-enroll. The exception is
 * re-thrown so the caller can route to PIN and show a re-enrollment prompt.
 */
class BiometricCryptoManager {

    /**
     * Returns an AES-256-GCM [Cipher] initialised for encryption. The cipher is tied to
     * the Keystore key which requires a fresh strong-biometric authentication before use.
     *
     * @throws KeyPermanentlyInvalidatedException if the biometric key has been invalidated
     *   (e.g. a new fingerprint was added). Callers must delete the key and fall back to PIN.
     */
    fun getEncryptCipher(): Cipher {
        val key = getOrCreateKey()
        return Cipher.getInstance(AES_GCM_NO_PADDING).also {
            try {
                it.init(Cipher.ENCRYPT_MODE, key)
            } catch (e: KeyPermanentlyInvalidatedException) {
                Timber.w("Biometric key permanently invalidated — deleting key")
                deleteKey()
                throw e
            }
        }
    }

    /**
     * Returns an AES-256-GCM [Cipher] initialised for decryption using the [iv] that was
     * captured during enrollment (i.e. from [Cipher.iv] after the first encryption).
     *
     * @param iv the 12-byte GCM initialisation vector stored at enrollment time.
     * @throws KeyPermanentlyInvalidatedException if the key has been invalidated.
     */
    fun getDecryptCipher(iv: ByteArray): Cipher {
        val key = getOrCreateKey()
        return Cipher.getInstance(AES_GCM_NO_PADDING).also {
            try {
                it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            } catch (e: KeyPermanentlyInvalidatedException) {
                Timber.w("Biometric key permanently invalidated during decrypt — deleting key")
                deleteKey()
                throw e
            }
        }
    }

    /**
     * Deletes the Keystore key. After this call biometric authentication will require
     * the user to re-enroll by setting a new PIN (which triggers key regeneration on the
     * next call to [getEncryptCipher]).
     */
    fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
                keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
                Timber.d("Biometric Keystore key deleted")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete biometric Keystore key")
        }
    }

    /** Returns true if the Keystore key currently exists. */
    fun hasKey(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        keyStore.getEntry(BIOMETRIC_KEY_ALIAS, null)?.let { entry ->
            if (entry is KeyStore.SecretKeyEntry) return entry.secretKey
        }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val specBuilder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            // Invalidate the key if the user adds a new biometric or removes all biometrics.
            // This forces re-enrollment on the next auth attempt, preventing a scenario where
            // an attacker adds their own fingerprint to bypass the existing key.
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: timeout=0 means the key can only be used immediately after auth,
            // with no grace period. AUTH_BIOMETRIC_STRONG ensures we never downgrade.
            specBuilder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            // API 29 fallback: -1 means "require auth every time" (same semantics as
            // timeout=0 on API 30+).
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    companion object {
        /** Stable alias used for the biometric proof key in AndroidKeyStore. */
        const val BIOMETRIC_KEY_ALIAS = "woow_odoo_biometric_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
