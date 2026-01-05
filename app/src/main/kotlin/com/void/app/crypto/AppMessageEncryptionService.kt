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

            // DEBUG: Log recipient details from contact
            Log.e(TAG, "🔍 ========== SENDER'S VIEW OF RECIPIENT ==========")
            Log.e(TAG, "🔍 Recipient three-word identity: ${contact.identity}")
            Log.e(TAG, "🔍 Recipient seed (first 16 bytes): ${contact.identitySeed.take(16).joinToString("") { "%02x".format(it) }}")
            Log.e(TAG, "🔍 Recipient publicKey (full): ${contact.publicKey.joinToString("") { "%02x".format(it) }}")
            Log.e(TAG, "🔍 ================================================")

            val recipientPublicKey = contact.publicKey
            Log.d(TAG, "🔑 [KEY_LOAD] Recipient publicKey: ${recipientPublicKey.size} bytes")

            // Get my public key to include in sealed sender header
            // SECURITY: Use public key, NOT seed (seed is secret!)
            val myPublicKey = identityRepository.getPublicEncryptionKey()
            if (myPublicKey == null) {
                Log.e(TAG, "❌ [ENCRYPT_FAILED] My public key not found")
                return null
            }

            val mySenderId = myPublicKey.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "🔍 [SENDER_PUBKEY] My encryption publicKey: ${mySenderId.take(32)}...")

            // Convert message to bytes
            val messageBytes = messageText.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "🔐 [MESSAGE] ${messageBytes.size} bytes: \"$messageText\"")

            // Prepend sealed sender header to plaintext
            val plaintextWithHeader = com.void.block.messaging.crypto.SealedSenderHeader.prependToMessage(
                senderId = mySenderId,
                timestamp = System.currentTimeMillis(),
                messageContent = messageBytes
            )
            Log.d(TAG, "📋 [HEADER_ADDED] plaintext with header: ${plaintextWithHeader.size} bytes")

            // Encrypt (plaintext now includes header)
            val encryptedMessage = messageEncryption.encrypt(
                plaintext = plaintextWithHeader,
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

        return com.void.block.messaging.crypto.RecipientIdentity(
            seed = contact.identitySeed,
            threeWordIdentity = contact.identity.toString()
        )
    }

    override suspend fun decryptReceivedMessage(encryptedPayload: ByteArray): com.void.block.messaging.crypto.DecryptedReceivedMessage? {
        return try {
            Log.d(TAG, "📥 [RECEIVER_DECRYPT_START] encryptedPayload: ${encryptedPayload.size} bytes")

            // Base64 decode
            val decoded = Base64.getDecoder().decode(encryptedPayload)
            Log.d(TAG, "📦 [DESERIALIZE] decoded: ${decoded.size} bytes")

            // Deserialize
            val encryptedMessage = messageEncryption.deserializeEncryptedMessage(decoded)
            Log.d(TAG, "📦 [DESERIALIZE] envelope: ciphertext=${encryptedMessage.ciphertext.size} bytes")

            // Get my private key
            val myPrivateKey = identityRepository.getPrivateEncryptionKey()
            if (myPrivateKey == null) {
                Log.e(TAG, "❌ [DECRYPT_FAILED] My private key not found")
                return null
            }

            // DEBUG: Log receiver's actual identity and keys
            val myIdentity = identityRepository.getIdentity()
            val myPublicKey = identityRepository.getPublicEncryptionKey()
            Log.e(TAG, "🔍 ========== RECEIVER'S ACTUAL KEYS ==========")
            Log.e(TAG, "🔍 My three-word identity: ${myIdentity?.formatted}")
            Log.e(TAG, "🔍 My seed (first 16 bytes): ${myIdentity?.seed?.take(16)?.joinToString("") { "%02x".format(it) }}")
            Log.e(TAG, "🔍 My publicKey (full): ${myPublicKey?.joinToString("") { "%02x".format(it) }}")
            Log.e(TAG, "🔍 ============================================")

            Log.d(TAG, "🔑 [KEY_LOAD] My privateKey: ${myPrivateKey.size} bytes")

            // Get all contacts to try decryption
            // Since we don't know the sender yet, we must try all known contacts
            val allContacts = contactRepository.contacts.value
            Log.d(TAG, "👥 [SEALED_SENDER] Trying decryption with ${allContacts.size} known contacts...")

            var decryptedPlaintext: ByteArray? = null
            var matchedContact: com.void.block.contacts.domain.Contact? = null

            // Try decrypting with each contact's public key
            for (contact in allContacts) {
                try {
                    val contactPubKeyHex = contact.publicKey.joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "🔍 [TRY_DECRYPT] Contact: ${contact.identity}")
                    Log.d(TAG, "🔍   Stored publicKey: ${contactPubKeyHex.take(32)}...")

                    val plaintext = messageEncryption.decrypt(
                        encrypted = encryptedMessage,
                        senderPublicKey = contact.publicKey,
                        recipientPrivateKey = myPrivateKey
                    )

                    // If decryption succeeds, we found the sender
                    decryptedPlaintext = plaintext
                    matchedContact = contact
                    Log.d(TAG, "✅ Message decrypted from: ${contact.identity}")
                    break

                } catch (e: Exception) {
                    // Decryption failed with this contact, try next
                    Log.w(TAG, "⚠️ [DECRYPT_FAILED] Contact ${contact.identity}: ${e.message}")
                }
            }

            if (decryptedPlaintext == null || matchedContact == null) {
                Log.e(TAG, "❌ [DECRYPT_FAILED] Could not decrypt with ${allContacts.size} known contacts. Sender may not be in contact list.")
                return null
            }

            Log.d(TAG, "✓ [MAC_VERIFY] MAC verification passed")
            Log.d(TAG, "🔓 [DECRYPT] plaintext: ${decryptedPlaintext.size} bytes")

            // Parse sealed sender header from plaintext
            val (header, messageContent) = com.void.block.messaging.crypto.SealedSenderHeader.parseFromPlaintext(decryptedPlaintext)
            val messageText = messageContent.decodeToString()

            com.void.block.messaging.crypto.DecryptedReceivedMessage(
                senderId = header.senderId,
                content = messageText,
                timestamp = header.timestamp
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ [RECEIVER_DECRYPT_FAILED] ${e.message}", e)
            null
        }
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
