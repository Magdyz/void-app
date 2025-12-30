package com.void.slate.network.models

import kotlinx.serialization.Serializable

/**
 * Request to send a message to a recipient.
 * The payload is already E2E encrypted by the crypto layer.
 */
@Serializable
data class MessageSendRequest(
    val messageId: String,
    val recipientIdentity: String,  // "word1.word2.word3"
    val encryptedPayload: ByteArray,  // Already E2E encrypted
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageSendRequest
        if (messageId != other.messageId) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        return result
    }
}

/**
 * Response from server after sending a message.
 */
@Serializable
data class MessageSendResponse(
    val success: Boolean,
    val serverTimestamp: Long = System.currentTimeMillis(),
    val messageId: String? = null,
    val error: String? = null
)

/**
 * A message received from the server.
 * The payload is still E2E encrypted and needs decryption by the crypto layer.
 */
@Serializable
data class ReceivedMessage(
    val messageId: String,
    val senderIdentity: String,  // "word1.word2.word3"
    val encryptedPayload: ByteArray,  // Still E2E encrypted
    val serverTimestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReceivedMessage
        if (messageId != other.messageId) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        return result
    }
}

/**
 * Request to exchange contact information with another user.
 * Includes public key bundle for Signal Protocol key exchange.
 */
@Serializable
data class ContactExchangeRequest(
    val requestId: String,
    val fromIdentity: String,  // "word1.word2.word3"
    val toIdentity: String,    // "word1.word2.word3"
    val publicKeyBundle: ByteArray,  // Contains X25519 + Ed25519 public keys
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContactExchangeRequest
        if (requestId != other.requestId) return false
        if (!publicKeyBundle.contentEquals(other.publicKeyBundle)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = requestId.hashCode()
        result = 31 * result + publicKeyBundle.contentHashCode()
        return result
    }
}

/**
 * Response from server after sending a contact request.
 */
@Serializable
data class ContactExchangeResponse(
    val success: Boolean,
    val accepted: Boolean = false,
    val contactId: String? = null,
    val error: String? = null
)

/**
 * State of message synchronization.
 */
@Serializable
data class MessageSyncState(
    val lastSyncTimestamp: Long,
    val pendingMessageIds: Set<String> = emptySet()
)
