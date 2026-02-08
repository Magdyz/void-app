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
    private var keyGenCallCount = 0
    
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
     * If they exist, verify they match the deterministic derivation.
     */
    private suspend fun ensureKeysExist() {
        val hasEncryptionKey = secureStorage.get(KEY_ENCRYPTION_PUBLIC) != null
        val hasIdentityKey = secureStorage.get(KEY_IDENTITY_PUBLIC) != null
        val hasMailboxSeed = secureStorage.get(KEY_MAILBOX_SEED) != null

        Log.d(TAG, "🔍 [KEY_CHECK] ensureKeysExist: enc=$hasEncryptionKey, id=$hasIdentityKey, mailbox=$hasMailboxSeed")

        if (!hasEncryptionKey || !hasIdentityKey || !hasMailboxSeed) {
            Log.e(TAG, "🚨 [KEY_MISSING] Keys not found for existing identity - generating now!")
            Log.e(TAG, "🚨 This will generate NEW keys that contacts don't know about!")
            generateAndStoreKeyPairs()
        } else {
            // Verify keys match deterministic derivation
            verifyKeyDerivation()
        }
    }

    /**
     * Verify that stored keys match the deterministic derivation from identity seed.
     * If they don't match, regenerate them.
     * This fixes issues where keys were generated before deterministic derivation was implemented.
     */
    private suspend fun verifyKeyDerivation() {
        val identity = _identity.value ?: return

        // Get stored public key
        val storedPublicKey = secureStorage.get(KEY_ENCRYPTION_PUBLIC) ?: return

        // Derive what the public key SHOULD be
        val expectedKeyPair = crypto.deriveKeyPairFromSeed(identity.seed, "encryption")

        // Compare
        if (!storedPublicKey.contentEquals(expectedKeyPair.publicKey)) {
            val storedHex = storedPublicKey.joinToString("") { "%02x".format(it) }
            val expectedHex = expectedKeyPair.publicKey.joinToString("") { "%02x".format(it) }
            val seedHex = identity.seed.take(16).joinToString("") { "%02x".format(it) }
            Log.e(TAG, "🚨 [KEY_ROTATION] Key mismatch detected! THIS WILL BREAK EXISTING CONTACTS!")
            Log.e(TAG, "🚨   Stored publicKey:  $storedHex")
            Log.e(TAG, "🚨   Expected publicKey: $expectedHex")
            Log.e(TAG, "🚨   Identity seed (first 16): $seedHex")

            // Regenerate keys using deterministic method
            generateAndStoreKeyPairs()

            val newPublicKey = secureStorage.get(KEY_ENCRYPTION_PUBLIC)
            val newHex = newPublicKey?.joinToString("") { "%02x".format(it) } ?: "null"
            Log.e(TAG, "🚨   New publicKey after regen: $newHex")
            Log.e(TAG, "🚨 [KEY_ROTATION] All contacts now have STALE keys. Messages will fail!")
        } else {
            Log.d(TAG, "✓ [KEY_VERIFY] Stored encryption key matches deterministic derivation")
        }
    }
    
    /**
     * Save a new identity to secure storage.
     * Also generates and stores cryptographic key pairs for encryption.
     */
    suspend fun saveIdentity(identity: Identity) {
        val seedHex = identity.seed.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "🔒 [IDENTITY_SAVE] Saving identity and generating key pairs")
        Log.d(TAG, "🔒 [IDENTITY_SAVE] Original seed: $seedHex")

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
        val finalPubKey = secureStorage.get(KEY_ENCRYPTION_PUBLIC)
        val finalPubKeyHex = finalPubKey?.joinToString("") { "%02x".format(it) } ?: "null"
        Log.d(TAG, "✓ [IDENTITY_SAVE] Identity saved. Final publicKey in storage: $finalPubKeyHex")
    }

    /**
     * Generate cryptographic key pairs for this identity.
     *
     * Generates:
     * - X25519 key pair for encryption (ECDH)
     * - Ed25519 key pair for signatures (identity verification)
     * - Mailbox seed for deriving blind mailbox addresses
     *
     * 🔒 SECURITY (Phase 3):
     * - Private keys are derived from identitySeed (NEVER SHARED)
     * - Mailbox seed is derived separately (CAN BE SHARED via QR code)
     * - Mailbox seed CANNOT derive private keys (domain separation via HKDF)
     */
    private suspend fun generateAndStoreKeyPairs() {
        keyGenCallCount++
        val callNum = keyGenCallCount
        val caller = Thread.currentThread().stackTrace.take(6).joinToString(" <- ") { it.methodName }
        Log.e(TAG, "🔑 [KEY_GEN #$callNum] generateAndStoreKeyPairs() called from: $caller")

        // Get identity seed
        val identity = _identity.value ?: getIdentity()
        require(identity != null) { "Identity must exist before generating keys" }

        val seedHex = identity.seed.joinToString("") { "%02x".format(it) }
        Log.e(TAG, "🔑 [KEY_GEN #$callNum] Using seed: $seedHex")

        // Derive encryption key pair (X25519 for ECDH)
        val encryptionKeyPair = crypto.deriveKeyPairFromSeed(identity.seed, "encryption")

        val pubKeyHex = encryptionKeyPair.publicKey.joinToString("") { "%02x".format(it) }
        val privKeyHex = encryptionKeyPair.privateKey.joinToString("") { "%02x".format(it) }
        Log.e(TAG, "🔑 [KEY_GEN #$callNum] Derived X25519 publicKey:  $pubKeyHex")
        Log.e(TAG, "🔑 [KEY_GEN #$callNum] Derived X25519 privateKey: $privKeyHex")

        // Store encryption keys in secure storage
        secureStorage.put(KEY_ENCRYPTION_PUBLIC, encryptionKeyPair.publicKey)
        secureStorage.put(KEY_ENCRYPTION_PRIVATE, encryptionKeyPair.privateKey)

        // Derive identity/signature key pair (Ed25519 for signatures)
        val identityKeyPair = crypto.deriveKeyPairFromSeed(identity.seed, "identity")

        val idPubKeyHex = identityKeyPair.publicKey.joinToString("") { "%02x".format(it) }
        Log.e(TAG, "🔑 [KEY_GEN #$callNum] Derived Ed25519 publicKey: $idPubKeyHex")

        // Store identity keys
        secureStorage.put(KEY_IDENTITY_PUBLIC, identityKeyPair.publicKey)
        secureStorage.put(KEY_IDENTITY_PRIVATE, identityKeyPair.privateKey)

        // 🆕 Derive mailbox seed (SAFE TO SHARE - cannot derive private keys)
        val mailboxSeed = crypto.derive(identity.seed, "mailbox-seed")

        Log.e(TAG, "🔑 [KEY_GEN #$callNum] Derived mailbox seed: ${mailboxSeed.take(16).joinToString("") { "%02x".format(it) }}...")

        // Store mailbox seed
        secureStorage.put(KEY_MAILBOX_SEED, mailboxSeed)

        // Verify what was actually stored
        val storedPubKey = secureStorage.get(KEY_ENCRYPTION_PUBLIC)
        val storedHex = storedPubKey?.joinToString("") { "%02x".format(it) } ?: "null"
        Log.e(TAG, "🔑 [KEY_GEN #$callNum] Verified stored publicKey: $storedHex")
        Log.e(TAG, "🔑 [KEY_GEN #$callNum] Match: ${storedHex == pubKeyHex}")
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
            val keyHex = key.joinToString("") { "%02x".format(it) }
            Log.e(TAG, "✓ [KEY_READ] Public encryption key: $keyHex")
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
     * Get the mailbox seed for this identity.
     *
     * 🔒 SECURITY: This seed is SAFE TO SHARE via QR code.
     * - It is used ONLY for deriving time-based mailbox addresses
     * - It CANNOT derive private encryption or signing keys
     * - Domain separation via HKDF prevents key derivation attacks
     *
     * Used for:
     * - Sending messages to this identity's mailbox
     * - Deriving blind mailbox addresses that rotate every 25 hours
     *
     * @return 32-byte mailbox seed, or null if not found
     */
    suspend fun getMailboxSeed(): ByteArray? {
        val seed = secureStorage.get(KEY_MAILBOX_SEED)
        if (seed == null) {
            Log.e(TAG, "❌ [KEY_ERROR] Mailbox seed not found in storage")
            Log.e(TAG, "   Storage key checked: $KEY_MAILBOX_SEED")
        } else {
            Log.d(TAG, "✓ [KEY_FOUND] Mailbox seed: ${seed.size} bytes")
        }
        return seed
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
        secureStorage.delete(KEY_MAILBOX_SEED)

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

        val seedHex = seed.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "🔒 [LOAD_FROM_STORAGE] Decrypted seed: $seedHex")
        Log.d(TAG, "🔒 [LOAD_FROM_STORAGE] Words: $wordsString")

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
        private const val KEY_MAILBOX_SEED = "identity.mailbox.seed"
    }
}
