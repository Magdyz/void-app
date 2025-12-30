package com.void.app.debug

import android.util.Log
import com.void.block.contacts.data.ContactRepository
import com.void.block.identity.data.IdentityRepository

/**
 * Debug helper for diagnosing encryption issues.
 * Remove in production builds.
 */
class CryptoDebugHelper(
    private val identityRepository: IdentityRepository,
    private val contactRepository: ContactRepository
) {
    companion object {
        private const val TAG = "VOID_CRYPTO_DEBUG"
    }

    /**
     * Verify that all required keys exist for encryption.
     * Call this on app start to diagnose key issues.
     */
    suspend fun verifyEncryptionSetup(): EncryptionStatus {
        Log.d(TAG, "=".repeat(60))
        Log.d(TAG, "🔍 ENCRYPTION SETUP VERIFICATION")
        Log.d(TAG, "=".repeat(60))

        // Check identity
        val identity = identityRepository.getIdentity()
        if (identity == null) {
            Log.e(TAG, "❌ No identity found")
            return EncryptionStatus.NO_IDENTITY
        }
        Log.d(TAG, "✓ Identity found: ${identity.formatted}")

        // Check encryption keys
        val publicEncryptionKey = identityRepository.getPublicEncryptionKey()
        val privateEncryptionKey = identityRepository.getPrivateEncryptionKey()

        if (publicEncryptionKey == null || privateEncryptionKey == null) {
            Log.e(TAG, "❌ Encryption keys missing:")
            Log.e(TAG, "   Public key: ${if (publicEncryptionKey == null) "MISSING" else "${publicEncryptionKey.size} bytes"}")
            Log.e(TAG, "   Private key: ${if (privateEncryptionKey == null) "MISSING" else "${privateEncryptionKey.size} bytes"}")
            return EncryptionStatus.KEYS_MISSING
        }

        Log.d(TAG, "✓ Encryption keys present:")
        Log.d(TAG, "   Public key: ${publicEncryptionKey.size} bytes")
        Log.d(TAG, "   Private key: ${privateEncryptionKey.size} bytes")

        // Check identity keys
        val publicIdentityKey = identityRepository.getPublicIdentityKey()
        val privateIdentityKey = identityRepository.getPrivateIdentityKey()

        if (publicIdentityKey == null || privateIdentityKey == null) {
            Log.w(TAG, "⚠️  Identity signing keys missing (not critical for Phase 2)")
        } else {
            Log.d(TAG, "✓ Identity keys present:")
            Log.d(TAG, "   Public key: ${publicIdentityKey.size} bytes")
            Log.d(TAG, "   Private key: ${privateIdentityKey.size} bytes")
        }

        // Check contacts
        val contacts = contactRepository.contacts.value
        Log.d(TAG, "📇 Contacts: ${contacts.size}")
        contacts.forEach { contact ->
            Log.d(TAG, "   - ${contact.identity}: publicKey=${contact.publicKey.size} bytes, identityKey=${contact.identityKey.size} bytes")
        }

        Log.d(TAG, "=".repeat(60))
        Log.d(TAG, "✅ ENCRYPTION SETUP COMPLETE - Ready to encrypt messages")
        Log.d(TAG, "=".repeat(60))

        return EncryptionStatus.READY
    }
}

enum class EncryptionStatus {
    READY,
    NO_IDENTITY,
    KEYS_MISSING
}
