package com.void.block.messaging.crypto

import com.void.block.messaging.domain.MessageContent

/**
 * Service interface for encrypting and decrypting messages.
 * Implementation lives in the app module which has access to all blocks.
 *
 * This allows messaging block to define what it needs without
 * creating compile-time dependencies on other blocks.
 */
interface MessageEncryptionService {
    /**
     * Encrypt message content for a recipient.
     *
     * @param content The message content to encrypt
     * @param recipientId The recipient's contact ID
     * @return Encrypted payload as Base64-encoded bytes, or null if encryption fails
     */
    suspend fun encryptMessage(content: MessageContent, recipientId: String): ByteArray?

    /**
     * Decrypt received message.
     *
     * @param encryptedPayload Base64-encoded encrypted payload
     * @param senderId The sender's contact ID
     * @return Decrypted text, or null if decryption fails
     */
    suspend fun decryptMessage(encryptedPayload: ByteArray, senderId: String): String?

    /**
     * Get recipient's three-word identity for network transmission.
     */
    suspend fun getRecipientIdentity(recipientId: String): String?
}
