package com.void.slate.crypto

/**
 * Cryptographic provider interface.
 * Blocks use this for all crypto operations, never directly calling libraries.
 */
interface CryptoProvider {
    
    /**
     * Generate a cryptographically secure random seed.
     */
    suspend fun generateSeed(bytes: Int = 32): ByteArray
    
    /**
     * Derive a deterministic value from a seed using a path.
     */
    suspend fun derive(seed: ByteArray, path: String): ByteArray
    
    /**
     * Hash data using a secure hash function.
     */
    suspend fun hash(data: ByteArray): ByteArray
    
    /**
     * Encrypt data with a key.
     */
    suspend fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptedData
    
    /**
     * Decrypt data with a key.
     */
    suspend fun decrypt(encrypted: EncryptedData, key: ByteArray): ByteArray
    
    /**
     * Generate a key pair for asymmetric encryption.
     */
    suspend fun generateKeyPair(): KeyPair

    /**
     * Derive a key pair deterministically from a seed.
     * Same seed + path always produces the same keys.
     * Used for manual contact exchange in Phase 2.
     *
     * @param seed The cryptographic seed to derive from
     * @param path Domain separation path (e.g., "encryption", "identity")
     */
    suspend fun deriveKeyPairFromSeed(seed: ByteArray, path: String): KeyPair

    /**
     * Sign data with a private key.
     */
    suspend fun sign(data: ByteArray, privateKey: ByteArray): ByteArray
    
    /**
     * Verify a signature with a public key.
     */
    suspend fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean

    /**
     * Perform ECDH key agreement to compute a shared secret.
     * For X25519: sharedSecret = scalar_mult(privateKey, publicKey)
     *
     * CRITICAL: This must be commutative:
     * - computeSharedSecret(alicePrivate, bobPublic) == computeSharedSecret(bobPrivate, alicePublic)
     *
     * @param privateKey 32-byte private key
     * @param publicKey 32-byte public key
     * @return 32-byte shared secret
     */
    suspend fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray
}

/**
 * Encrypted data container.
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val tag: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedData) return false
        return ciphertext.contentEquals(other.ciphertext) &&
               nonce.contentEquals(other.nonce) &&
               tag.contentEquals(other.tag)
    }
    
    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + (tag?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Key pair for asymmetric encryption.
 */
data class KeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return publicKey.contentEquals(other.publicKey) &&
               privateKey.contentEquals(other.privateKey)
    }
    
    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}

/**
 * Interface for secure key derivation (for rhythm key).
 */
interface KeyDerivation {
    /**
     * Derive a key from rhythm timing data.
     * Uses Argon2id for memory-hard derivation.
     */
    suspend fun deriveFromRhythm(
        timings: List<Long>,
        pressures: List<Float>,
        salt: ByteArray
    ): ByteArray
    
    /**
     * Generate a BIP-39 recovery phrase from a seed.
     */
    suspend fun seedToMnemonic(seed: ByteArray): List<String>
    
    /**
     * Recover a seed from a BIP-39 mnemonic.
     */
    suspend fun mnemonicToSeed(mnemonic: List<String>): ByteArray
}
