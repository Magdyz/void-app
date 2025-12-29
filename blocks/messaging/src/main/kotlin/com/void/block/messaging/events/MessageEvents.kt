package com.void.block.messaging.events

import com.void.slate.event.Event

/**
 * Events emitted by the Messaging block.
 */
sealed class MessageEvent : Event {

    /**
     * Emitted when a new message is sent.
     */
    data class MessageSent(
        val messageId: String,
        val conversationId: String,
        val recipientId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a message is received from a contact.
     */
    data class MessageReceived(
        val messageId: String,
        val conversationId: String,
        val senderId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a message is delivered.
     */
    data class MessageDelivered(
        val messageId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a message is read by the recipient.
     */
    data class MessageRead(
        val messageId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a message fails to send.
     */
    data class MessageFailed(
        val messageId: String,
        val error: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a message expires and is deleted.
     */
    data class MessageExpired(
        val messageId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a message is deleted by the user.
     */
    data class MessageDeleted(
        val messageId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a conversation is created.
     */
    data class ConversationCreated(
        val conversationId: String,
        val contactId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a conversation is deleted.
     */
    data class ConversationDeleted(
        val conversationId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when a conversation is marked as read.
     */
    data class ConversationRead(
        val conversationId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()

    /**
     * Emitted when typing indicator should be shown.
     */
    data class TypingIndicator(
        val conversationId: String,
        val isTyping: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : MessageEvent()
}
