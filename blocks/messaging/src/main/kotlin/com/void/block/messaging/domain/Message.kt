package com.void.block.messaging.domain

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a single message in VOID.
 *
 * Messages are:
 * - End-to-end encrypted using Signal Protocol (Double Ratchet)
 * - Stored locally in encrypted database
 * - Can have expiry times (disappearing messages)
 * - Include read receipts and delivery status
 */
@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,              // Which conversation this belongs to
    val senderId: String,                     // Sender's contact ID (or "me")
    val recipientId: String,                  // Recipient's contact ID (or "me")
    val content: MessageContent,              // The actual message content
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENDING,
    val direction: MessageDirection,          // INCOMING or OUTGOING
    val encryptedPayload: ByteArray? = null, // Encrypted message data
    val expiresAt: Long? = null,             // When message should be deleted (null = never)
    val readAt: Long? = null,                // When message was read
    val deliveredAt: Long? = null,           // When message was delivered
) {
    /**
     * Check if message has expired.
     */
    fun isExpired(): Boolean {
        return expiresAt != null && System.currentTimeMillis() > expiresAt
    }

    /**
     * Check if message is read.
     */
    fun isRead(): Boolean {
        return readAt != null
    }

    /**
     * Check if message is delivered.
     */
    fun isDelivered(): Boolean {
        return deliveredAt != null || status == MessageStatus.DELIVERED || status == MessageStatus.READ
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Message

        if (id != other.id) return false
        if (conversationId != other.conversationId) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Message content types.
 */
@Serializable
sealed class MessageContent {
    /**
     * Plain text message.
     */
    @Serializable
    data class Text(val text: String) : MessageContent()

    /**
     * Image message (encrypted bytes).
     */
    @Serializable
    data class Image(
        val data: ByteArray,
        val mimeType: String = "image/jpeg"
    ) : MessageContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Image

            if (!data.contentEquals(other.data)) return false
            if (mimeType != other.mimeType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            return result
        }
    }

    /**
     * File attachment.
     */
    @Serializable
    data class File(
        val data: ByteArray,
        val fileName: String,
        val mimeType: String
    ) : MessageContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as File

            if (!data.contentEquals(other.data)) return false
            if (fileName != other.fileName) return false
            if (mimeType != other.mimeType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + mimeType.hashCode()
            return result
        }
    }

    /**
     * System message (contact added, etc.).
     */
    @Serializable
    data class System(val message: String) : MessageContent()
}

/**
 * Message delivery status.
 */
@Serializable
enum class MessageStatus {
    SENDING,      // Being sent
    SENT,         // Sent to server
    DELIVERED,    // Delivered to recipient
    READ,         // Read by recipient
    FAILED,       // Failed to send
    EXPIRED       // Message has expired
}

/**
 * Message direction.
 */
@Serializable
enum class MessageDirection {
    INCOMING,     // Received from contact
    OUTGOING      // Sent by me
}

/**
 * Conversation (thread) between two parties.
 */
@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val contactId: String,                    // Who we're talking to
    val lastMessage: Message? = null,         // Most recent message
    val lastMessageAt: Long? = null,          // Timestamp of last message
    val unreadCount: Int = 0,                 // Number of unread messages
    val isPinned: Boolean = false,            // Pinned to top of list
    val isMuted: Boolean = false,             // Notifications muted
    val createdAt: Long = System.currentTimeMillis(),
    val expiryDuration: Long? = null,         // Default expiry for new messages (ms)
) {
    /**
     * Get preview text for conversation list.
     */
    fun getPreviewText(): String {
        return when (val content = lastMessage?.content) {
            is MessageContent.Text -> content.text
            is MessageContent.Image -> "\uD83D\uDDBC️ Image"
            is MessageContent.File -> "\uD83D\uDCC4 ${content.fileName}"
            is MessageContent.System -> content.message
            null -> "No messages yet"
        }
    }

    /**
     * Check if conversation has unread messages.
     */
    fun hasUnreadMessages(): Boolean {
        return unreadCount > 0
    }
}

/**
 * Message draft (unsent message being composed).
 */
@Serializable
data class MessageDraft(
    val conversationId: String,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis()
)
