package com.void.block.messaging.data

import android.util.Log
import com.void.block.messaging.crypto.MessageEncryptionService
import com.void.block.messaging.domain.Conversation
import com.void.block.messaging.domain.Message
import com.void.block.messaging.domain.MessageContent
import com.void.block.messaging.domain.MessageDirection
import com.void.block.messaging.domain.MessageDraft
import com.void.block.messaging.domain.MessageStatus
import com.void.slate.network.NetworkClient
import com.void.slate.network.models.MessageSendRequest
import com.void.slate.network.models.ReceivedMessage
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for managing messages and conversations.
 * Stores all messages in encrypted storage and syncs with network.
 */
class MessageRepository(
    private val storage: SecureStorage,
    private val networkClient: NetworkClient? = null,  // Optional for now, null = offline mode
    private val encryptionService: MessageEncryptionService? = null  // Optional - null = no encryption
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messagesCache = mutableMapOf<String, MutableStateFlow<List<Message>>>()

    companion object {
        private const val TAG = "VOID_SECURITY"
        private const val KEY_PREFIX_MESSAGE = "message."
        private const val KEY_PREFIX_CONVERSATION = "conversation."
        private const val KEY_PREFIX_DRAFT = "draft."
        private const val KEY_CONVERSATION_IDS = "conversation.all_ids"
        private const val KEY_MESSAGE_IDS_PREFIX = "conversation.message_ids."
    }

    /**
     * Load all conversations from storage.
     */
    suspend fun loadConversations() {
        val ids = getStoredConversationIds()
        val loadedConversations = ids.mapNotNull { id ->
            getConversation(id)
        }.sortedByDescending { it.lastMessageAt ?: 0 }

        _conversations.value = loadedConversations
    }

    /**
     * Get messages for a conversation.
     * Returns a Flow that automatically updates.
     */
    fun getMessagesFlow(conversationId: String): StateFlow<List<Message>> {
        return _messagesCache.getOrPut(conversationId) {
            MutableStateFlow(emptyList())
        }.asStateFlow()
    }

    /**
     * Load messages for a conversation.
     */
    suspend fun loadMessages(conversationId: String) {
        val messageIds = getMessageIds(conversationId)
        val messages = messageIds.mapNotNull { id ->
            getMessage(id)
        }.sortedBy { it.timestamp }

        val flow = _messagesCache.getOrPut(conversationId) {
            MutableStateFlow(emptyList())
        }
        flow.value = messages
    }

    /**
     * Send a message (add to conversation).
     *
     * Stores locally first, then transmits via network if available.
     */
    suspend fun sendMessage(message: Message) {
        // 1. Store message locally first (with SENDING status)
        storeMessageLocally(message)

        // 2. Send via network if available
        networkClient?.let { client ->
            sendMessageViaNetwork(message, client)
        }
    }

    /**
     * Store message in local storage.
     */
    private suspend fun storeMessageLocally(message: Message) {
        // Store message
        val messageKey = "$KEY_PREFIX_MESSAGE${message.id}"
        val messageJson = json.encodeToString(message)
        storage.put(messageKey, messageJson.toByteArray())

        // Add to message IDs list
        val messageIds = getMessageIds(message.conversationId).toMutableSet()
        messageIds.add(message.id)
        saveMessageIds(message.conversationId, messageIds)

        // Update conversation
        var conversation = getConversation(message.conversationId)
        if (conversation == null) {
            // Create new conversation if it doesn't exist
            conversation = Conversation(
                id = message.conversationId,
                contactId = message.recipientId,
                lastMessage = message,
                lastMessageAt = message.timestamp,
                unreadCount = 0
            )
            createConversation(conversation)
        } else {
            // Update existing conversation
            conversation = conversation.copy(
                lastMessage = message,
                lastMessageAt = message.timestamp
            )
            updateConversation(conversation)
        }

        // Update in-memory messages
        val flow = _messagesCache.getOrPut(message.conversationId) {
            MutableStateFlow(emptyList())
        }
        flow.value = flow.value + message
    }

    /**
     * Send message via network.
     * Encrypts message content before transmission.
     */
    private suspend fun sendMessageViaNetwork(message: Message, client: NetworkClient) {
        // Get recipient identity
        val recipientIdentity = encryptionService?.getRecipientIdentity(message.recipientId)
        if (recipientIdentity == null) {
            Log.e(TAG, "❌ [SEND_FAILED] Recipient identity not found: ${message.recipientId}")
            updateMessageStatus(message.id, MessageStatus.FAILED)
            return
        }

        // Encrypt message content
        val encryptedPayload = if (encryptionService != null) {
            val encrypted = encryptionService.encryptMessage(message.content, message.recipientId)
            if (encrypted == null) {
                updateMessageStatus(message.id, MessageStatus.FAILED)
                return
            }
            encrypted
        } else {
            // Fallback: no encryption (for testing only)
            Log.w(TAG, "⚠️  [NO_ENCRYPTION] Sending unencrypted message (testing mode)")
            message.encryptedPayload ?: json.encodeToString(message).toByteArray()
        }

        val request = MessageSendRequest(
            messageId = message.id,
            recipientIdentity = recipientIdentity,
            encryptedPayload = encryptedPayload,
            timestamp = message.timestamp
        )

        client.sendMessage(request)
            .onSuccess {
                // Update message status to SENT
                updateMessageStatus(message.id, MessageStatus.SENT)
                Log.d(TAG, "✓ [MESSAGE_SENT] messageId=${message.id}")
            }
            .onFailure { error ->
                // Update message status to FAILED
                updateMessageStatus(message.id, MessageStatus.FAILED)
                Log.e(TAG, "❌ [MESSAGE_FAILED] ${error.message}", error)
                // TODO: Emit error event via EventBus
            }
    }

    /**
     * Receive a message (from another contact).
     */
    suspend fun receiveMessage(message: Message) {
        // Store message
        val messageKey = "$KEY_PREFIX_MESSAGE${message.id}"
        val messageJson = json.encodeToString(message)
        storage.put(messageKey, messageJson.toByteArray())

        // Add to message IDs list
        val messageIds = getMessageIds(message.conversationId).toMutableSet()
        messageIds.add(message.id)
        saveMessageIds(message.conversationId, messageIds)

        // Update conversation
        var conversation = getConversation(message.conversationId)
        if (conversation == null) {
            // Create new conversation if it doesn't exist
            conversation = Conversation(
                id = message.conversationId,
                contactId = message.senderId,
                lastMessage = message,
                lastMessageAt = message.timestamp,
                unreadCount = 1
            )
            createConversation(conversation)
        } else {
            // Update existing conversation
            conversation = conversation.copy(
                lastMessage = message,
                lastMessageAt = message.timestamp,
                unreadCount = conversation.unreadCount + 1
            )
            updateConversation(conversation)
        }

        // Update in-memory messages
        val flow = _messagesCache.getOrPut(message.conversationId) {
            MutableStateFlow(emptyList())
        }
        flow.value = flow.value + message
    }

    /**
     * Update message status (delivered, read, etc.).
     */
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val message = getMessage(messageId) ?: return

        val updatedMessage = when (status) {
            MessageStatus.DELIVERED -> message.copy(
                status = status,
                deliveredAt = System.currentTimeMillis()
            )
            MessageStatus.READ -> message.copy(
                status = status,
                readAt = System.currentTimeMillis()
            )
            else -> message.copy(status = status)
        }

        // Update in storage
        val messageKey = "$KEY_PREFIX_MESSAGE$messageId"
        val messageJson = json.encodeToString(updatedMessage)
        storage.put(messageKey, messageJson.toByteArray())

        // Update in-memory cache
        val flow = _messagesCache[updatedMessage.conversationId]
        flow?.value = flow?.value?.map {
            if (it.id == messageId) updatedMessage else it
        } ?: emptyList()
    }

    /**
     * Mark conversation as read (reset unread count).
     */
    suspend fun markConversationAsRead(conversationId: String) {
        val conversation = getConversation(conversationId) ?: return

        val updated = conversation.copy(unreadCount = 0)
        updateConversation(updated)

        // Mark all messages in conversation as read
        val messages = _messagesCache[conversationId]?.value ?: emptyList()
        messages.filter { !it.isRead() }.forEach { message ->
            updateMessageStatus(message.id, MessageStatus.READ)
        }
    }

    /**
     * Delete a message.
     */
    suspend fun deleteMessage(messageId: String) {
        val message = getMessage(messageId) ?: return

        // Remove from storage
        val messageKey = "$KEY_PREFIX_MESSAGE$messageId"
        storage.delete(messageKey)

        // Remove from message IDs list
        val messageIds = getMessageIds(message.conversationId).toMutableSet()
        messageIds.remove(messageId)
        saveMessageIds(message.conversationId, messageIds)

        // Update in-memory cache
        val flow = _messagesCache[message.conversationId]
        flow?.value = flow?.value?.filter { it.id != messageId } ?: emptyList()

        // Update conversation if this was the last message
        val conversation = getConversation(message.conversationId)
        if (conversation?.lastMessage?.id == messageId) {
            val remainingMessages = flow?.value ?: emptyList()
            val newLastMessage = remainingMessages.lastOrNull()
            updateConversation(
                conversation.copy(
                    lastMessage = newLastMessage,
                    lastMessageAt = newLastMessage?.timestamp
                )
            )
        }
    }

    /**
     * Delete expired messages.
     */
    suspend fun deleteExpiredMessages() {
        val now = System.currentTimeMillis()

        _messagesCache.forEach { (conversationId, flow) ->
            val expiredMessages = flow.value.filter { it.isExpired() }
            expiredMessages.forEach { message ->
                deleteMessage(message.id)
            }
        }
    }

    /**
     * Delete a conversation and all its messages.
     */
    suspend fun deleteConversation(conversationId: String) {
        // Delete all messages
        val messageIds = getMessageIds(conversationId)
        messageIds.forEach { messageId ->
            val messageKey = "$KEY_PREFIX_MESSAGE$messageId"
            storage.delete(messageKey)
        }

        // Delete message IDs list
        storage.delete("$KEY_MESSAGE_IDS_PREFIX$conversationId")

        // Delete conversation
        val conversationKey = "$KEY_PREFIX_CONVERSATION$conversationId"
        storage.delete(conversationKey)

        // Remove from conversation IDs list
        val conversationIds = getStoredConversationIds().toMutableSet()
        conversationIds.remove(conversationId)
        saveConversationIds(conversationIds)

        // Update in-memory lists
        _conversations.value = _conversations.value.filter { it.id != conversationId }
        _messagesCache.remove(conversationId)
    }

    // ═══════════════════════════════════════════════════════════════
    // Drafts
    // ═══════════════════════════════════════════════════════════════

    /**
     * Save a message draft.
     */
    suspend fun saveDraft(draft: MessageDraft) {
        val draftKey = "$KEY_PREFIX_DRAFT${draft.conversationId}"
        val draftJson = json.encodeToString(draft)
        storage.put(draftKey, draftJson.toByteArray())
    }

    /**
     * Get message draft for conversation.
     */
    suspend fun getDraft(conversationId: String): MessageDraft? {
        val draftKey = "$KEY_PREFIX_DRAFT$conversationId"
        val bytes = storage.get(draftKey) ?: return null
        return try {
            json.decodeFromString<MessageDraft>(bytes.decodeToString())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete message draft.
     */
    suspend fun deleteDraft(conversationId: String) {
        val draftKey = "$KEY_PREFIX_DRAFT$conversationId"
        storage.delete(draftKey)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

    private suspend fun getMessage(messageId: String): Message? {
        val key = "$KEY_PREFIX_MESSAGE$messageId"
        val bytes = storage.get(key) ?: return null
        return try {
            json.decodeFromString<Message>(bytes.decodeToString())
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getConversation(conversationId: String): Conversation? {
        val key = "$KEY_PREFIX_CONVERSATION$conversationId"
        val bytes = storage.get(key) ?: return null
        return try {
            json.decodeFromString<Conversation>(bytes.decodeToString())
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun createConversation(conversation: Conversation) {
        // Store conversation
        val conversationKey = "$KEY_PREFIX_CONVERSATION${conversation.id}"
        val conversationJson = json.encodeToString(conversation)
        storage.put(conversationKey, conversationJson.toByteArray())

        // Add to conversation IDs list
        val ids = getStoredConversationIds().toMutableSet()
        ids.add(conversation.id)
        saveConversationIds(ids)

        // Update in-memory list
        _conversations.value = (_conversations.value + conversation)
            .sortedByDescending { it.lastMessageAt ?: 0 }
    }

    private suspend fun updateConversation(conversation: Conversation) {
        // Store conversation
        val conversationKey = "$KEY_PREFIX_CONVERSATION${conversation.id}"
        val conversationJson = json.encodeToString(conversation)
        storage.put(conversationKey, conversationJson.toByteArray())

        // Update in-memory list
        _conversations.value = _conversations.value.map {
            if (it.id == conversation.id) conversation else it
        }.sortedByDescending { it.lastMessageAt ?: 0 }
    }

    private suspend fun getStoredConversationIds(): Set<String> {
        val bytes = storage.get(KEY_CONVERSATION_IDS) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(bytes.decodeToString())
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun saveConversationIds(ids: Set<String>) {
        val idsJson = json.encodeToString(ids)
        storage.put(KEY_CONVERSATION_IDS, idsJson.toByteArray())
    }

    private suspend fun getMessageIds(conversationId: String): Set<String> {
        val key = "$KEY_MESSAGE_IDS_PREFIX$conversationId"
        val bytes = storage.get(key) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(bytes.decodeToString())
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun saveMessageIds(conversationId: String, ids: Set<String>) {
        val key = "$KEY_MESSAGE_IDS_PREFIX$conversationId"
        val idsJson = json.encodeToString(ids)
        storage.put(key, idsJson.toByteArray())
    }

    // ═══════════════════════════════════════════════════════════════
    // Network Sync
    // ═══════════════════════════════════════════════════════════════

    /**
     * Sync messages from the network.
     *
     * Polls for new messages and stores them locally.
     * Returns the number of new messages received.
     */
    suspend fun syncMessages(since: Long? = null): Int {
        val client = networkClient ?: return 0

        var newMessageCount = 0

        client.receiveMessages(since)
            .onSuccess { receivedMessages ->
                Log.d(TAG, "📥 [SYNC] Received ${receivedMessages.size} messages from server")
                receivedMessages.forEach { networkMessage ->
                    val message = parseReceivedMessage(networkMessage)
                    if (message != null) {
                        receiveMessage(message)
                        newMessageCount++
                    } else {
                        Log.w(TAG, "⚠️ [SYNC] Failed to parse message ${networkMessage.
                        messageId}")
                    }
                }
            }
            .onFailure { error ->
                Log.e(TAG, "❌ [SYNC_FAILED] ${error.message}", error)
            }

        return newMessageCount
    }

    /**
     * Parse a received network message into a Message domain object.
     * Decrypts the message content.
     */
    private suspend fun parseReceivedMessage(networkMessage: ReceivedMessage): Message? {
        return try {
            // Decrypt the message
            // For Phase 2, we'll use a placeholder sender ID
            // In Phase 3, sender identity will be included in the message metadata
            val senderId = "unknown" // TODO: Extract from message metadata

            val plaintext = if (encryptionService != null) {
                val decrypted = encryptionService.decryptMessage(networkMessage.encryptedPayload, senderId)
                if (decrypted == null) {
                    Log.e(TAG, "❌ [DECRYPT_FAILED] Failed to decrypt message")
                    return null
                }
                decrypted
            } else {
                // Fallback: no encryption (for testing only)
                Log.w(TAG, "⚠️  [NO_ENCRYPTION] Receiving unencrypted message (testing mode)")
                networkMessage.encryptedPayload.decodeToString()
            }

            // Create message
            val message = Message(
                id = networkMessage.messageId,
                conversationId = senderId,
                senderId = senderId,
                recipientId = "me",
                content = MessageContent.Text(plaintext),
                direction = MessageDirection.INCOMING,
                timestamp = networkMessage.serverTimestamp,
                status = MessageStatus.DELIVERED,
                deliveredAt = networkMessage.serverTimestamp,
                encryptedPayload = networkMessage.encryptedPayload
            )

            Log.d(TAG, "✓ [MESSAGE_RECEIVED] messageId=${message.id}, content=\"$plaintext\"")
            message
        } catch (e: Exception) {
            Log.e(TAG, "❌ [PARSE_FAILED] ${e.message}", e)
            null
        }
    }

    /**
     * Get the last sync timestamp.
     * Used to poll only for messages since last sync.
     */
    suspend fun getLastSyncTimestamp(): Long? {
        val bytes = storage.get("network.last_sync_timestamp") ?: return null
        return try {
            bytes.decodeToString().toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Update the last sync timestamp.
     */
    suspend fun updateLastSyncTimestamp(timestamp: Long = System.currentTimeMillis()) {
        storage.put("network.last_sync_timestamp", timestamp.toString().toByteArray())
    }

    /**
     * Clear all messages and conversations (for panic wipe).
     */
    suspend fun clearAll() {
        // Delete all conversations and messages
        val conversationIds = getStoredConversationIds()
        conversationIds.forEach { conversationId ->
            deleteConversation(conversationId)
        }

        // Clear in-memory caches
        _conversations.value = emptyList()
        _messagesCache.clear()
    }
}
