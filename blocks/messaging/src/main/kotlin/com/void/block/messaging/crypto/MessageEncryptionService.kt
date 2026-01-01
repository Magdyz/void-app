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
     * Decrypt received message WITHOUT knowing sender ID upfront (Sealed Sender).
     *
     * This method:
     * 1. Decrypts the message using recipient's private key
     * 2. Parses the sealed sender header to extract senderId
     * 3. Returns both senderId and message content
     *
     * Use this for receiving messages where sender is unknown until after decryption.
     *
     * @param encryptedPayload Base64-encoded encrypted payload
     * @return DecryptedReceivedMessage containing senderId, content, and timestamp, or null if decryption fails
     */
    suspend fun decryptReceivedMessage(encryptedPayload: ByteArray): DecryptedReceivedMessage?

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
 * Result of decrypting a received message with sealed sender header.
 */
data class DecryptedReceivedMessage(
    /** Sender ID extracted from sealed sender header (hex-encoded seed) */
    val senderId: String,
    /** Decrypted message content */
    val content: String,
    /** Timestamp from sealed sender header */
    val timestamp: Long
)

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
