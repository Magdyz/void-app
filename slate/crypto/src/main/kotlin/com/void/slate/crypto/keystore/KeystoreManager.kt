package com.void.slate.crypto.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages Android Keystore operations.
 * Provides hardware-backed key storage when available.
 *
 * SECURITY: Keys generated here NEVER leave the secure hardware.
 * All encryption/decryption operations happen inside the secure element.
 */
open class KeystoreManager(private val context: Context?) {

    private val keyStore: KeyStore? = try {
        if (context != null) {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }
        } else null
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    /**
     * Generate a new AES-256 key in the Keystore.
     *
     * @param alias Unique identifier for the key
     * @param requireAuth If true, requires biometric auth before use
     * @param useStrongBox If true, uses dedicated secure element (if available)
     * @return The generated SecretKey
     */
    open fun generateKey(
        alias: String,
        requireAuth: Boolean = false,
        useStrongBox: Boolean = true
    ): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        // Use StrongBox if available (dedicated secure element)
        if (useStrongBox && hasStrongBox()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(true)
                }
            } catch (e: Exception) {
                // StrongBox not available, fall back to regular TEE
            }
        }

        // Require user authentication if specified
        if (requireAuth) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        30, // Valid for 30 seconds after auth
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                builder
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(30)
            }
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Check if StrongBox (secure element) is available.
     * StrongBox is a hardware security module separate from the main processor.
     */
    open fun hasStrongBox(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context != null) {
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_STRONGBOX_KEYSTORE
            )
        } else {
            false
        }
    }

    /**
     * Encrypt data with a Keystore key.
     * Returns IV prepended to ciphertext: [IV (12 bytes)][Ciphertext][Auth Tag (16 bytes)]
     *
     * @param alias The key alias to use
     * @param plaintext Data to encrypt
     * @return Encrypted data with IV prepended
     * @throws KeyNotFoundException if the key doesn't exist
     */
    open fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val key = getKey(alias) ?: throw KeyNotFoundException(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext: [IV (12 bytes)][Ciphertext][Tag]
        return ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    /**
     * Decrypt data with a Keystore key.
     * Expects IV prepended to ciphertext.
     *
     * @param alias The key alias to use
     * @param data Encrypted data with IV prepended
     * @return Decrypted plaintext
     * @throws KeyNotFoundException if the key doesn't exist
     */
    open fun decrypt(alias: String, data: ByteArray): ByteArray {
        val key = getKey(alias) ?: throw KeyNotFoundException(alias)

        // Extract IV (first 12 bytes for GCM)
        val iv = data.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = data.sliceArray(GCM_IV_LENGTH until data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return cipher.doFinal(ciphertext)
    }

    /**
     * Get a key from the Keystore.
     *
     * @param alias The key alias
     * @return The SecretKey or null if not found
     */
    open fun getKey(alias: String): SecretKey? {
        return try {
            keyStore?.getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a key exists in the Keystore.
     *
     * @param alias The key alias to check
     * @return true if the key exists
     */
    open fun hasKey(alias: String): Boolean {
        return try {
            keyStore?.containsAlias(alias) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Delete a key from the Keystore.
     *
     * @param alias The key alias to delete
     */
    open fun deleteKey(alias: String) {
        try {
            if (keyStore?.containsAlias(alias) == true) {
                keyStore.deleteEntry(alias)
            }
        } catch (e: Exception) {
            // Key might not exist or already deleted
        }
    }

    /**
     * Delete all VOID keys (for panic wipe).
     * This removes all keys with the "void_" prefix.
     */
    open fun deleteAllVoidKeys() {
        try {
            keyStore?.aliases()?.toList()
                ?.filter { it.startsWith("void_") }
                ?.forEach { keyStore.deleteEntry(it) }
        } catch (e: Exception) {
            // Best effort deletion
        }
    }

    /**
     * Get information about the Keystore security level.
     *
     * @return Security level description
     */
    open fun getSecurityLevel(): SecurityLevel {
        return when {
            hasStrongBox() -> SecurityLevel.STRONGBOX
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> SecurityLevel.TEE
            else -> SecurityLevel.SOFTWARE
        }
    }
}

/**
 * Security levels for key storage.
 */
enum class SecurityLevel {
    /** Hardware security module (strongest) */
    STRONGBOX,

    /** Trusted Execution Environment */
    TEE,

    /** Software-only (weakest, not recommended) */
    SOFTWARE
}

/**
 * Exception thrown when a key is not found in the Keystore.
 */
class KeyNotFoundException(alias: String) : Exception("Key not found in Keystore: $alias")
