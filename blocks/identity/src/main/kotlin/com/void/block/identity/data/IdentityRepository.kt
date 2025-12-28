package com.void.block.identity.data

import com.void.block.identity.domain.Identity
import com.void.slate.crypto.CryptoProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for identity storage and retrieval.
 * Uses secure storage for the cryptographic seed.
 */
class IdentityRepository(
    private val secureStorage: SecureStorage,
    private val crypto: CryptoProvider
) {
    
    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: Flow<Identity?> = _identity.asStateFlow()
    
    /**
     * Get the current identity, loading from storage if needed.
     */
    suspend fun getIdentity(): Identity? {
        _identity.value?.let { return it }
        
        // Try to load from secure storage
        val stored = loadFromStorage()
        _identity.value = stored
        return stored
    }
    
    /**
     * Save a new identity to secure storage.
     */
    suspend fun saveIdentity(identity: Identity) {
        // Encrypt the seed before storage
        val encryptedSeed = crypto.encrypt(
            plaintext = identity.seed,
            key = getStorageKey()
        )
        
        // Store encrypted seed and words
        secureStorage.put(KEY_SEED, encryptedSeed.ciphertext)
        secureStorage.put(KEY_NONCE, encryptedSeed.nonce)
        secureStorage.put(KEY_WORDS, identity.words.joinToString(","))
        secureStorage.put(KEY_CREATED, identity.createdAt.toString())
        
        _identity.value = identity
    }
    
    /**
     * Delete the current identity.
     */
    suspend fun deleteIdentity() {
        secureStorage.delete(KEY_SEED)
        secureStorage.delete(KEY_NONCE)
        secureStorage.delete(KEY_WORDS)
        secureStorage.delete(KEY_CREATED)
        _identity.value = null
    }
    
    /**
     * Check if an identity exists.
     */
    suspend fun hasIdentity(): Boolean {
        return secureStorage.contains(KEY_SEED)
    }
    
    private suspend fun loadFromStorage(): Identity? {
        if (!hasIdentity()) return null
        
        val encryptedSeed = secureStorage.get(KEY_SEED) ?: return null
        val nonce = secureStorage.get(KEY_NONCE) ?: return null
        val wordsString = secureStorage.getString(KEY_WORDS) ?: return null
        val createdAt = secureStorage.getString(KEY_CREATED)?.toLongOrNull() ?: return null
        
        // Decrypt the seed
        val seed = crypto.decrypt(
            encrypted = com.void.slate.crypto.EncryptedData(
                ciphertext = encryptedSeed,
                nonce = nonce
            ),
            key = getStorageKey()
        )
        
        return Identity(
            words = wordsString.split(","),
            seed = seed,
            createdAt = createdAt
        )
    }
    
    private suspend fun getStorageKey(): ByteArray {
        // In production, this comes from Android Keystore
        // For now, derive from a device-specific value
        return crypto.derive(
            seed = secureStorage.getDeviceId(),
            path = "identity/storage"
        )
    }
    
    companion object {
        private const val KEY_SEED = "identity.seed"
        private const val KEY_NONCE = "identity.nonce"
        private const val KEY_WORDS = "identity.words"
        private const val KEY_CREATED = "identity.created"
    }
}

/**
 * Interface for secure storage.
 * Implemented by slate/storage module.
 */
interface SecureStorage {
    suspend fun put(key: String, value: ByteArray)
    suspend fun get(key: String): ByteArray?
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String)
    suspend fun delete(key: String)
    suspend fun contains(key: String): Boolean
    suspend fun getDeviceId(): ByteArray
}
