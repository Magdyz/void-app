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
     * Get recipient's identity with seed for mailbox derivation.
     *
     * @param recipientId The recipient's contact ID
     * @return RecipientIdentity containing seed and three-word identity, or null if not found
     */
    suspend fun getRecipientIdentity(recipientId: String): RecipientIdentity?

    /**
     * Get the current user's own identity with seed.
     * Used for deriving own mailbox addresses when fetching messages.
     *
     * @return Own identity with seed, or null if identity not initialized
     */
    suspend fun getOwnIdentity(): RecipientIdentity?
}

/**
 * Recipient identity for message sending and mailbox derivation.
 */
data class RecipientIdentity(
    /** The recipient's 32-byte identity seed for mailbox derivation */
    val seed: ByteArray,
    /** The recipient's three-word identity (e.g., "ghost.paper.forty") */
    val threeWordIdentity: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RecipientIdentity

        if (!seed.contentEquals(other.seed)) return false
        if (threeWordIdentity != other.threeWordIdentity) return false

        return true
    }

    override fun hashCode(): Int {
        var result = seed.contentHashCode()
        result = 31 * result + threeWordIdentity.hashCode()
        return result
    }
}
