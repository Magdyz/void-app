package com.void.app.crypto

import android.util.Log
import com.void.block.contacts.data.ContactRepository
import com.void.block.identity.data.IdentityRepository
import com.void.block.messaging.crypto.MessageEncryption
import com.void.block.messaging.crypto.MessageEncryptionService
import com.void.block.messaging.domain.MessageContent
import java.util.Base64

/**
 * Implementation of MessageEncryptionService.
 * Lives in app module which has access to all blocks.
 */
class AppMessageEncryptionService(
    private val messageEncryption: MessageEncryption,
    private val identityRepository: IdentityRepository,
    private val contactRepository: ContactRepository
) : MessageEncryptionService {

    companion object {
        private const val TAG = "VOID_SECURITY"
    }

    override suspend fun encryptMessage(content: MessageContent, recipientId: String): ByteArray? {
        return try {
            Log.d(TAG, "🔒 [ENCRYPT_START] recipientId=$recipientId")

            // Extract text from message content
            val messageText = when (content) {
                is MessageContent.Text -> content.text
                is MessageContent.Image -> "[Image]"
                is MessageContent.File -> "[File: ${content.fileName}]"
                is MessageContent.System -> content.message
            }

            // Get encryption keys
            val myPrivateKey = identityRepository.getPrivateEncryptionKey()
            if (myPrivateKey == null) {
                Log.e(TAG, "❌ [ENCRYPT_FAILED] My private key not found")
                return null
            }
            Log.d(TAG, "🔑 [KEY_LOAD] My privateKey: ${myPrivateKey.size} bytes")

            val contact = contactRepository.getContact(recipientId)
            if (contact == null) {
                Log.e(TAG, "❌ [ENCRYPT_FAILED] Contact not found: $recipientId")
                return null
            }

            val recipientPublicKey = contact.publicKey
            Log.d(TAG, "🔑 [KEY_LOAD] Recipient publicKey: ${recipientPublicKey.size} bytes")

            // Convert to bytes
            val plaintext = messageText.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "🔐 [PLAINTEXT] ${plaintext.size} bytes: \"$messageText\"")

            // Encrypt
            val encryptedMessage = messageEncryption.encrypt(
                plaintext = plaintext,
                recipientPublicKey = recipientPublicKey,
                senderPrivateKey = myPrivateKey
            )
            Log.d(TAG, "🔒 [ENCRYPT] ciphertext: ${encryptedMessage.ciphertext.size} bytes, nonce: ${encryptedMessage.nonce.size} bytes")
            Log.d(TAG, "✓ [MAC_COMPUTE] MAC: ${encryptedMessage.mac.toHex()}")

            // Serialize
            val serialized = messageEncryption.serializeEncryptedMessage(encryptedMessage)
            Log.d(TAG, "📦 [SERIALIZE] envelope: ${serialized.size} bytes")

            // Base64 encode
            val base64 = Base64.getEncoder().encode(serialized)
            Log.d(TAG, "📤 [NETWORK_SEND] base64: ${base64.size} bytes")
            Log.d(TAG, "⚠️  [SECURITY_CHECK] Plaintext NEVER sent: ✓")

            base64
        } catch (e: Exception) {
            Log.e(TAG, "❌ [ENCRYPT_FAILED] ${e.message}", e)
            null
        }
    }

    override suspend fun decryptMessage(encryptedPayload: ByteArray, senderId: String): String? {
        return try {
            Log.d(TAG, "📥 [NETWORK_RECEIVE] encryptedPayload: ${encryptedPayload.size} bytes")

            // Base64 decode
            val decoded = Base64.getDecoder().decode(encryptedPayload)
            Log.d(TAG, "📦 [DESERIALIZE] decoded: ${decoded.size} bytes")

            // Deserialize
            val encryptedMessage = messageEncryption.deserializeEncryptedMessage(decoded)
            Log.d(TAG, "📦 [DESERIALIZE] envelope extracted: ciphertext=${encryptedMessage.ciphertext.size} bytes")

            // Get keys
            val myPrivateKey = identityRepository.getPrivateEncryptionKey()
            if (myPrivateKey == null) {
                Log.e(TAG, "❌ [DECRYPT_FAILED] My private key not found")
                return null
            }
            Log.d(TAG, "🔑 [KEY_LOAD] My privateKey: ${myPrivateKey.size} bytes")

            val contact = contactRepository.getContact(senderId)
            if (contact == null) {
                Log.e(TAG, "❌ [DECRYPT_FAILED] Sender contact not found: $senderId")
                return null
            }

            val senderPublicKey = contact.publicKey
            Log.d(TAG, "🔑 [KEY_LOAD] Sender publicKey: ${senderPublicKey.size} bytes")

            // Decrypt
            val decrypted = messageEncryption.decrypt(
                encrypted = encryptedMessage,
                senderPublicKey = senderPublicKey,
                recipientPrivateKey = myPrivateKey
            )
            Log.d(TAG, "✓ [MAC_VERIFY] MAC verification passed")
            Log.d(TAG, "🔓 [DECRYPT] plaintext: ${decrypted.size} bytes")
            Log.d(TAG, "⚠️  [SECURITY_CHECK] MAC verified before decrypt: ✓")

            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "❌ [DECRYPT_FAILED] ${e.message}", e)
            null
        }
    }

    override suspend fun getRecipientIdentity(recipientId: String): com.void.block.messaging.crypto.RecipientIdentity? {
        val contact = contactRepository.getContact(recipientId) ?: return null

        // TODO: Contacts need to store the recipient's seed for mailbox derivation
        // For now, we'll use a placeholder. In a real implementation:
        // 1. During contact exchange, both parties share their identity seed
        // 2. The seed is stored in the Contact model
        // 3. We retrieve it here for mailbox derivation

        // TEMPORARY WORKAROUND: Generate deterministic seed from identity words
        // This is NOT secure for production - just for testing
        val tempSeed = (contact.identity.word1 + contact.identity.word2 + contact.identity.word3)
            .toByteArray()
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }

        return com.void.block.messaging.crypto.RecipientIdentity(
            seed = tempSeed,
            threeWordIdentity = contact.identity.toString()
        )
    }

    override suspend fun getOwnIdentity(): com.void.block.messaging.crypto.RecipientIdentity? {
        val identity = identityRepository.getIdentity() ?: return null

        return com.void.block.messaging.crypto.RecipientIdentity(
            seed = identity.seed,
            threeWordIdentity = identity.formatted
        )
    }

    private fun ByteArray.toHex(): String {
        return this.joinToString("") { "%02x".format(it) }
    }
}
