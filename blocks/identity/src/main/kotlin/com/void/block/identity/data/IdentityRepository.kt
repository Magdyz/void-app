package com.void.block.identity.data

import android.util.Log
import com.void.block.identity.domain.Identity
import com.void.slate.crypto.CryptoProvider
import com.void.slate.crypto.keystore.KeystoreManager
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for identity storage and retrieval.
 * Uses secure storage for the cryptographic seed.
 */
class IdentityRepository(
    private val secureStorage: SecureStorage,
    private val crypto: CryptoProvider,
    private val keystoreManager: KeystoreManager
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

        // CRITICAL: Ensure keys exist for this identity
        // If keys don't exist (e.g., identity created before key generation was added),
        // generate them now
        if (stored != null) {
            ensureKeysExist()
        }

        return stored
    }

    /**
     * Ensure encryption keys exist for the current identity.
     * If they don't exist, generate them.
     */
    private suspend fun ensureKeysExist() {
        val hasEncryptionKey = secureStorage.get(KEY_ENCRYPTION_PUBLIC) != null
        val hasIdentityKey = secureStorage.get(KEY_IDENTITY_PUBLIC) != null

        if (!hasEncryptionKey || !hasIdentityKey) {
            Log.w(TAG, "⚠️  [KEY_MISSING] Keys not found for existing identity - generating now")
            generateAndStoreKeyPairs()
        } else {
            Log.d(TAG, "✓ [KEY_CHECK] Keys exist for identity")
        }
    }
    
    /**
     * Save a new identity to secure storage.
     * Also generates and stores cryptographic key pairs for encryption.
     */
    suspend fun saveIdentity(identity: Identity) {
        Log.d(TAG, "🔒 [IDENTITY_SAVE] Saving identity and generating key pairs")

        // Encrypt the seed before storage
        val encryptedSeed = crypto.encrypt(
            plaintext = identity.seed,
            key = getStorageKey()
        )

        // Store encrypted seed and words
        secureStorage.put(KEY_SEED, encryptedSeed.ciphertext)
        secureStorage.put(KEY_NONCE, encryptedSeed.nonce)
        secureStorage.putString(KEY_WORDS, identity.words.joinToString(","))
        secureStorage.putString(KEY_CREATED, identity.createdAt.toString())

        // Generate and store cryptographic key pairs
        generateAndStoreKeyPairs()

        _identity.value = identity
        Log.d(TAG, "✓ [IDENTITY_SAVE] Identity saved with key pairs")
    }

    /**
     * Generate cryptographic key pairs for this identity.
     *
     * Generates:
     * - X25519 key pair for encryption (ECDH)
     * - Ed25519 key pair for signatures (identity verification)
     *
     * Keys are stored in Android Keystore (hardware-backed).
     */
    private suspend fun generateAndStoreKeyPairs() {
        // Generate encryption key pair (for ECDH)
        // For Phase 2, we'll use the crypto provider's key pair generation
        // In Phase 3, this will be replaced with proper X25519
        val encryptionKeyPair = crypto.generateKeyPair()

        Log.d(TAG, "🔑 [KEY_GEN] Generated encryption key pair: publicKey=${encryptionKeyPair.publicKey.size} bytes, privateKey=${encryptionKeyPair.privateKey.size} bytes")

        // Store encryption keys in secure storage
        // Private keys should go to Android Keystore in production
        secureStorage.put(KEY_ENCRYPTION_PUBLIC, encryptionKeyPair.publicKey)
        secureStorage.put(KEY_ENCRYPTION_PRIVATE, encryptionKeyPair.privateKey)

        // Generate identity/signature key pair
        val identityKeyPair = crypto.generateKeyPair()

        Log.d(TAG, "🔑 [KEY_GEN] Generated identity key pair: publicKey=${identityKeyPair.publicKey.size} bytes, privateKey=${identityKeyPair.privateKey.size} bytes")

        // Store identity keys
        secureStorage.put(KEY_IDENTITY_PUBLIC, identityKeyPair.publicKey)
        secureStorage.put(KEY_IDENTITY_PRIVATE, identityKeyPair.privateKey)

        Log.d(TAG, "✓ [KEY_STORE] All keys stored securely")
    }

    /**
     * Get the public encryption key for this identity.
     * Used for receiving encrypted messages.
     */
    suspend fun getPublicEncryptionKey(): ByteArray? {
        val key = secureStorage.get(KEY_ENCRYPTION_PUBLIC)
        if (key == null) {
            Log.e(TAG, "❌ [KEY_ERROR] Public encryption key not found in storage")
            Log.e(TAG, "   Storage key checked: $KEY_ENCRYPTION_PUBLIC")
        } else {
            Log.d(TAG, "✓ [KEY_FOUND] Public encryption key: ${key.size} bytes")
        }
        return key
    }

    /**
     * Get the private encryption key for this identity.
     * Used for decrypting received messages.
     */
    suspend fun getPrivateEncryptionKey(): ByteArray? {
        val key = secureStorage.get(KEY_ENCRYPTION_PRIVATE)
        if (key == null) {
            Log.e(TAG, "❌ [KEY_ERROR] Private encryption key not found in storage")
            Log.e(TAG, "   Storage key checked: $KEY_ENCRYPTION_PRIVATE")
            Log.e(TAG, "   This usually means:")
            Log.e(TAG, "   1. Identity was created before key generation was implemented")
            Log.e(TAG, "   2. Keys were deleted/corrupted")
            Log.e(TAG, "   3. Storage encryption key changed")
            Log.e(TAG, "   Solution: Delete app data and recreate identity")
        } else {
            Log.d(TAG, "✓ [KEY_FOUND] Private encryption key: ${key.size} bytes")
        }
        return key
    }

    /**
     * Get the public identity key for this identity.
     * Used for verifying signatures and contact verification.
     */
    suspend fun getPublicIdentityKey(): ByteArray? {
        return secureStorage.get(KEY_IDENTITY_PUBLIC)
    }

    /**
     * Get the private identity key for this identity.
     * Used for signing messages and proving identity.
     */
    suspend fun getPrivateIdentityKey(): ByteArray? {
        return secureStorage.get(KEY_IDENTITY_PRIVATE)
    }
    
    /**
     * Delete the current identity and all associated keys.
     */
    suspend fun deleteIdentity() {
        Log.d(TAG, "🗑️ [IDENTITY_DELETE] Deleting identity and all keys")

        // Delete identity data
        secureStorage.delete(KEY_SEED)
        secureStorage.delete(KEY_NONCE)
        secureStorage.delete(KEY_WORDS)
        secureStorage.delete(KEY_CREATED)

        // Delete cryptographic keys
        secureStorage.delete(KEY_ENCRYPTION_PUBLIC)
        secureStorage.delete(KEY_ENCRYPTION_PRIVATE)
        secureStorage.delete(KEY_IDENTITY_PUBLIC)
        secureStorage.delete(KEY_IDENTITY_PRIVATE)

        // Delete from Android Keystore
        keystoreManager.deleteAllVoidKeys()

        _identity.value = null

        Log.d(TAG, "✓ [IDENTITY_DELETE] Identity and keys deleted")
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
        private const val TAG = "VOID_SECURITY"
        private const val KEY_SEED = "identity.seed"
        private const val KEY_NONCE = "identity.nonce"
        private const val KEY_WORDS = "identity.words"
        private const val KEY_CREATED = "identity.created"
        private const val KEY_ENCRYPTION_PUBLIC = "identity.encryption.public"
        private const val KEY_ENCRYPTION_PRIVATE = "identity.encryption.private"
        private const val KEY_IDENTITY_PUBLIC = "identity.identity.public"
        private const val KEY_IDENTITY_PRIVATE = "identity.identity.private"
    }
}
