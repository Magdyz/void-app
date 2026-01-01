package com.void.slate.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory fake implementation of CryptoProvider for testing.
 * Uses standard Java crypto without Tink dependency.
 */
class FakeCryptoProvider : CryptoProvider {

    private val secureRandom = SecureRandom()

    override suspend fun generateSeed(bytes: Int): ByteArray {
        return ByteArray(bytes).apply {
            secureRandom.nextBytes(this)
        }
    }

    override suspend fun derive(seed: ByteArray, path: String): ByteArray {
        // Simple HKDF-like derivation for testing
        val combined = seed + path.toByteArray(Charsets.UTF_8)
        return hash(combined)
    }

    override suspend fun hash(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    override suspend fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptedData {
        require(key.size == 32) { "Key must be 32 bytes" }

        val nonce = ByteArray(12).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext)

        return EncryptedData(
            ciphertext = ciphertext,
            nonce = nonce
        )
    }

    override suspend fun decrypt(encrypted: EncryptedData, key: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, encrypted.nonce)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        return cipher.doFinal(encrypted.ciphertext)
    }

    override suspend fun generateKeyPair(): KeyPair {
        // Simple fake keypair for testing
        val publicKey = generateSeed(32)
        val privateKey = generateSeed(64)
        return KeyPair(publicKey, privateKey)
    }

    override suspend fun deriveKeyPairFromSeed(seed: ByteArray, path: String): KeyPair {
        // Deterministic derivation for testing
        val derivedSeed = derive(seed, path)
        val privateKey = derivedSeed + derivedSeed // 64 bytes
        return KeyPair(
            publicKey = derivedSeed,  // 32 bytes
            privateKey = privateKey.copyOf(64)
        )
    }

    override suspend fun sign(data: ByteArray, privateKey: ByteArray): ByteArray {
        // Fake signature for testing
        return hash(data + privateKey)
    }

    override suspend fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        // Fake verification for testing - not cryptographically sound
        return signature.size == 32
    }

    override suspend fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        // Fake ECDH for testing - deterministic but not cryptographically sound
        // XOR private and public keys, then hash for determinism
        val combined = ByteArray(32)
        for (i in 0 until 32) {
            combined[i] = (privateKey[i].toInt() xor publicKey[i].toInt()).toByte()
        }
        return hash(combined)
    }
}
