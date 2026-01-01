package com.void.slate.crypto.impl

import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.mac.MacConfig
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.signature.Ed25519PrivateKeyManager
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.void.slate.crypto.CryptoProvider
import com.void.slate.crypto.EncryptedData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * Tink-based implementation of CryptoProvider.
 *
 * Uses Google Tink for cryptographic operations:
 * - AES-256-GCM for symmetric encryption
 * - HKDF for key derivation
 * - SHA-256 for hashing
 * - Ed25519 for signatures
 */
class TinkCryptoProvider : CryptoProvider {

    private val secureRandom = SecureRandom()

    init {
        try {
            // Register Tink configurations
            AeadConfig.register()
            MacConfig.register()
            SignatureConfig.register()
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Failed to initialize Tink crypto provider", e)
        }
    }

    override suspend fun generateSeed(bytes: Int): ByteArray = withContext(Dispatchers.Default) {
        ByteArray(bytes).apply {
            secureRandom.nextBytes(this)
        }
    }

    override suspend fun derive(seed: ByteArray, path: String): ByteArray = withContext(Dispatchers.Default) {
        try {
            // Use HKDF to derive a key from the seed
            // path is used as the "info" parameter for domain separation
            val salt = ByteArray(32) // Empty salt for deterministic derivation
            val info = path.toByteArray(Charsets.UTF_8)

            Hkdf.computeHkdf(
                "HmacSha256",
                seed,
                salt,
                info,
                32 // Output 32 bytes (256 bits)
            )
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Key derivation failed", e)
        }
    }

    override suspend fun hash(data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        MessageDigest.getInstance("SHA-256").digest(data)
    }

    override suspend fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptedData = withContext(Dispatchers.Default) {
        try {
            require(key.size == 32) { "Key must be 32 bytes (256 bits)" }

            // Generate a random nonce (12 bytes for GCM)
            val nonce = ByteArray(12).apply { secureRandom.nextBytes(this) }

            // Create AEAD primitive with the provided key
            val aead = createAead(key)

            // Encrypt with nonce as associated data
            val ciphertext = aead.encrypt(plaintext, nonce)

            EncryptedData(
                ciphertext = ciphertext,
                nonce = nonce
            )
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Encryption failed", e)
        }
    }

    override suspend fun decrypt(encrypted: EncryptedData, key: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        try {
            require(key.size == 32) { "Key must be 32 bytes (256 bits)" }

            // Create AEAD primitive with the provided key
            val aead = createAead(key)

            // Decrypt with nonce as associated data
            aead.decrypt(encrypted.ciphertext, encrypted.nonce)
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Decryption failed", e)
        }
    }

    override suspend fun generateKeyPair(): com.void.slate.crypto.KeyPair = withContext(Dispatchers.Default) {
        try {
            // Generate Ed25519 key pair for signatures
            val privateKeysetHandle = KeysetHandle.generateNew(
                Ed25519PrivateKeyManager.ed25519Template()
            )

            // Get public keyset handle
            val publicKeysetHandle = privateKeysetHandle.publicKeysetHandle

            // Serialize keysets to bytes
            val privateKeyBytes = serializeKeyset(privateKeysetHandle)
            val publicKeyBytes = serializeKeyset(publicKeysetHandle)

            com.void.slate.crypto.KeyPair(
                publicKey = publicKeyBytes,
                privateKey = privateKeyBytes
            )
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Key pair generation failed", e)
        }
    }

    override suspend fun deriveKeyPairFromSeed(seed: ByteArray, path: String): com.void.slate.crypto.KeyPair = withContext(Dispatchers.Default) {
        try {
            // Derive a 32-byte key from the seed using HKDF
            val derivedSeed = derive(seed, path)

            // Choose key type based on path:
            // - "encryption" → X25519 keys (for ECDH key agreement)
            // - "identity" → Ed25519 keys (for signatures)
            val keyPair = when (path) {
                "encryption" -> {
                    // Generate X25519 key pair for encryption (ECDH)
                    // This is deterministic - same seed always produces same keys
                    val privateKey = derivedSeed  // Use derived seed as X25519 private key
                    val publicKey = X25519.publicFromPrivate(privateKey)
                    Pair(publicKey, privateKey)
                }
                "identity" -> {
                    // Generate Ed25519 key pair for signatures
                    val ed25519KeyPair = com.google.crypto.tink.subtle.Ed25519Sign.KeyPair.newKeyPairFromSeed(derivedSeed)
                    Pair(ed25519KeyPair.publicKey, ed25519KeyPair.privateKey)
                }
                else -> {
                    // Default to Ed25519 for backward compatibility
                    val ed25519KeyPair = com.google.crypto.tink.subtle.Ed25519Sign.KeyPair.newKeyPairFromSeed(derivedSeed)
                    Pair(ed25519KeyPair.publicKey, ed25519KeyPair.privateKey)
                }
            }

            com.void.slate.crypto.KeyPair(
                publicKey = keyPair.first,
                privateKey = keyPair.second
            )
        } catch (e: Exception) {
            throw RuntimeException("Deterministic key pair derivation failed", e)
        }
    }

    override suspend fun sign(data: ByteArray, privateKey: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        try {
            // Deserialize the private keyset
            val keysetHandle = deserializeKeyset(privateKey)

            // Get the signing primitive
            val signer = keysetHandle.getPrimitive(PublicKeySign::class.java)

            // Sign the data
            signer.sign(data)
        } catch (e: GeneralSecurityException) {
            throw RuntimeException("Signing failed", e)
        }
    }

    override suspend fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = withContext(Dispatchers.Default) {
        try {
            // Deserialize the public keyset
            val keysetHandle = deserializeKeyset(publicKey)

            // Get the verification primitive
            val verifier = keysetHandle.getPrimitive(PublicKeyVerify::class.java)

            // Verify the signature
            verifier.verify(signature, data)
            true // If no exception thrown, verification succeeded
        } catch (e: GeneralSecurityException) {
            // Verification failed
            false
        }
    }

    override suspend fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        try {
            require(privateKey.size == 32) { "Private key must be 32 bytes" }
            require(publicKey.size == 32) { "Public key must be 32 bytes" }

            // Perform X25519 ECDH
            X25519.computeSharedSecret(privateKey, publicKey)
        } catch (e: Exception) {
            throw RuntimeException("ECDH key agreement failed", e)
        }
    }

    /**
     * Create an AEAD primitive from a raw key.
     * Uses AES-256-GCM.
     */
    private fun createAead(key: ByteArray): Aead {
        // Tink's AES-GCM expects a specific key format
        // For now, we'll use javax.crypto directly as Tink's key import is complex
        return TinkAeadWrapper(key)
    }

    /**
     * Wrapper around javax.crypto for AES-GCM operations.
     * This provides Tink's Aead interface using standard crypto.
     */
    private class TinkAeadWrapper(private val key: ByteArray) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")

            // Use associated data as IV
            val iv = associatedData.copyOf(12) // GCM needs 12 bytes
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)

            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            return cipher.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")

            // Use associated data as IV
            val iv = associatedData.copyOf(12) // GCM needs 12 bytes
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)

            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            return cipher.doFinal(ciphertext)
        }
    }

    /**
     * Serialize a KeysetHandle to bytes.
     *
     * WARNING: This uses CleartextKeysetHandle which stores keys unencrypted.
     * In production, consider using encrypted keyset storage.
     */
    private fun serializeKeyset(keysetHandle: KeysetHandle): ByteArray {
        val outputStream = ByteArrayOutputStream()
        CleartextKeysetHandle.write(
            keysetHandle,
            BinaryKeysetWriter.withOutputStream(outputStream)
        )
        return outputStream.toByteArray()
    }

    /**
     * Deserialize a KeysetHandle from bytes.
     *
     * WARNING: This uses CleartextKeysetHandle which expects unencrypted keys.
     * In production, consider using encrypted keyset storage.
     */
    private fun deserializeKeyset(keysetBytes: ByteArray): KeysetHandle {
        val inputStream = ByteArrayInputStream(keysetBytes)
        return CleartextKeysetHandle.read(
            BinaryKeysetReader.withInputStream(inputStream)
        )
    }
}
