package com.void.block.messaging.data

import android.util.Log
import com.void.block.messaging.crypto.MessageEncryptionService
import com.void.block.messaging.domain.Conversation
import com.void.block.messaging.domain.Message
import com.void.block.messaging.domain.MessageContent
import com.void.block.messaging.domain.MessageDirection
import com.void.block.messaging.domain.MessageDraft
import com.void.block.messaging.domain.MessageStatus
import com.void.slate.network.supabase.FetchMailboxClient
import com.void.slate.network.supabase.MessageSender
import com.void.slate.network.supabase.MessageFetcher
import com.void.slate.network.supabase.MessageRecord
import com.void.slate.network.supabase.DeliveryAckService
import com.void.slate.network.mailbox.MailboxDerivation
import com.void.slate.network.sync.SeenMessageTracker
import com.void.slate.storage.SecureStorage
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.void.block.messaging.sync.SyncDebouncer

/**
 * Repository for managing messages and conversations.
 * Stores all messages in encrypted storage and syncs with network.
 */
class MessageRepository(
    private val storage: SecureStorage,
    private val messageSender: MessageSender? = null,  // Optional for now, null = offline mode
    private val messageFetcher: MessageFetcher? = null,  // Optional for now, null = offline mode (legacy)
    private val fetchMailboxClient: FetchMailboxClient? = null,  // Optional - Poisson Ghost protocol (preferred)
    private val mailboxDerivation: MailboxDerivation? = null,  // Optional for fetching
    private val encryptionService: MessageEncryptionService? = null,  // Optional - null = no encryption
    private val publicKeyToContactId: (suspend (String) -> String?)? = null,  // Optional - converts public key hex to contact UUID
    private val isContactMutuallyVerified: (suspend (String) -> Boolean)? = null,  // Optional - checks if contact is mutually verified
    private val markContactMutuallyVerified: (suspend (String) -> Unit)? = null,  // Optional - marks contact as mutually verified
    private val conversationStateManager: com.void.block.messaging.adaptive.ConversationStateManager? = null,  // Optional - for INSTANT-VOID adaptive mode
    private val syncDebouncer: SyncDebouncer? = null,  // Optional - prevents excessive syncs (5 min interval)
    private val deliveryAckService: DeliveryAckService? = null,  // Optional - for instant destruction ACKs
    private val seenMessageTracker: SeenMessageTracker? = null  // Optional - for replay attack protection
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messagesCache = mutableMapOf<String, MutableStateFlow<List<Message>>>()

    // Fire-and-forget scope for non-critical background tasks (decoys, etc.)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "VOID_SECURITY"
        private const val KEY_PREFIX_MESSAGE = "message."
        private const val KEY_PREFIX_CONVERSATION = "conversation."
        private const val KEY_PREFIX_DRAFT = "draft."
        private const val KEY_CONVERSATION_IDS = "conversation.all_ids"
        private const val KEY_MESSAGE_IDS_PREFIX = "conversation.message_ids."
        private const val HANDSHAKE_PREFIX = "__VOID_HANDSHAKE__:"
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
        Log.d(TAG, "📥 [REPO_RECEIVED] MessageRepository.sendMessage() called")
        Log.d(TAG, "   🆔 MessageID: ${message.id}")
        Log.d(TAG, "   👤 RecipientID: ${message.recipientId}")

        // 1. Store message locally first (with SENDING status)
        Log.d(TAG, "💾 [STORING_LOCAL] Storing message locally...")
        storeMessageLocally(message)
        Log.d(TAG, "✅ [STORED_LOCAL] Message stored locally")

        // 2. Send via network if available
        if (messageSender == null) {
            Log.e(TAG, "❌ [NO_SENDER] messageSender is NULL! Message will not be sent to network!")
            Log.e(TAG, "   ⚠️  This means MessageSender was not injected via DI")
        } else {
            Log.d(TAG, "✅ [SENDER_AVAILABLE] messageSender is available, proceeding to network send")
            sendMessageViaNetwork(message, messageSender)
        }

        // 3. Update conversation state for INSTANT-VOID adaptive mode (if enabled)
        conversationStateManager?.updateConversation(message.conversationId, message.timestamp)
        Log.d(TAG, "✅ [REPO_COMPLETE] MessageRepository.sendMessage() completed")
    }

    /**
     * Store message in local storage.
     */
    private suspend fun storeMessageLocally(message: Message) = withContext(Dispatchers.IO) {
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
     * Send message via network using Supabase.
     * Encrypts message content before transmission.
     */
    private suspend fun sendMessageViaNetwork(message: Message, sender: MessageSender) {
        Log.d(TAG, "🌐 [NETWORK_SEND_START] sendMessageViaNetwork() called")
        Log.d(TAG, "   🆔 MessageID: ${message.id}")
        Log.d(TAG, "   👤 RecipientID: ${message.recipientId}")

        // Get recipient identity
        Log.d(TAG, "🔍 [GETTING_IDENTITY] Looking up recipient identity...")
        if (encryptionService == null) {
            Log.e(TAG, "❌ [NO_ENCRYPTION_SERVICE] encryptionService is NULL!")
            updateMessageStatus(message.id, MessageStatus.FAILED)
            return
        }

        val recipientIdentity = encryptionService.getRecipientIdentity(message.recipientId)
        if (recipientIdentity == null) {
            Log.e(TAG, "❌ [SEND_FAILED] Recipient identity not found: ${message.recipientId}")
            Log.e(TAG, "   ⚠️  This means the contact doesn't have identity info (seed/public key)")
            updateMessageStatus(message.id, MessageStatus.FAILED)
            return
        }

        // 🆕 SECURITY AUDIT: Log recipient details for mailbox verification
        Log.d(TAG, "✅ [IDENTITY_FOUND] Recipient identity found")
        Log.d(TAG, "   👤 ContactID: ${message.recipientId}")
        Log.d(TAG, "   🏷️  Three-word identity: ${recipientIdentity.threeWordIdentity}")
        Log.d(TAG, "   🔑 MailboxSeed (first 16 bytes): ${recipientIdentity.seed.take(16).joinToString("") { "%02x".format(it) }}")

        // ═══════════════════════════════════════════════════════════════
        // MUTUAL VERIFICATION CHECK
        // Only handshake messages can be sent to unverified contacts.
        // Regular messages are queued as PENDING_VERIFICATION until
        // the handshake completes (both parties scanned each other's QR).
        // ═══════════════════════════════════════════════════════════════
        if (message.content !is MessageContent.Handshake) {
            val isVerified = isContactMutuallyVerified?.invoke(message.recipientId) ?: true
            if (!isVerified) {
                Log.d(TAG, "⏳ [PENDING_VERIFICATION] Contact ${message.recipientId} not mutually verified")
                Log.d(TAG, "   📨 Queuing message as PENDING_VERIFICATION and sending handshake ping")
                updateMessageStatus(message.id, MessageStatus.PENDING_VERIFICATION)
                sendHandshakePing(message.recipientId)
                return
            }
        }

        // Encrypt message content
        Log.d(TAG, "🔐 [ENCRYPTING] Encrypting message content...")
        val encryptedPayload = if (encryptionService != null) {
            val encrypted = encryptionService.encryptMessage(message.content, message.recipientId)
            if (encrypted == null) {
                Log.e(TAG, "❌ [ENCRYPTION_FAILED] Failed to encrypt message")
                updateMessageStatus(message.id, MessageStatus.FAILED)
                return
            }
            Log.d(TAG, "✅ [ENCRYPTED] Message encrypted successfully (${encrypted.size} bytes)")
            encrypted
        } else {
            // Fallback: no encryption (for testing only)
            Log.w(TAG, "⚠️  [NO_ENCRYPTION] Sending unencrypted message (testing mode)")
            message.encryptedPayload ?: json.encodeToString(message).toByteArray()
        }

        // Send message to Supabase using recipient's mailbox seed
        Log.d(TAG, "📡 [CALLING_SENDER] Calling sender.sendMessage()...")
        Log.d(TAG, "   📦 Payload size: ${encryptedPayload.size} bytes")
        Log.d(TAG, "   ⏰ Timestamp: ${message.timestamp}")

        val result = sender.sendMessage(
            recipientMailboxSeed = recipientIdentity.seed,
            encryptedPayload = encryptedPayload,
            timestamp = message.timestamp
        )

        Log.d(TAG, "✅ [SENDER_RETURNED] sender.sendMessage() returned")

        result.onSuccess { messageId ->
            // Update message status to SENT
            Log.d(TAG, "✅ [SEND_SUCCESS] Message sent successfully!")
            Log.d(TAG, "   🆔 LocalMessageID: ${message.id}")
            Log.d(TAG, "   🌐 SupabaseID: $messageId")
            updateMessageStatus(message.id, MessageStatus.SENT)
            Log.d(TAG, "✓ [MESSAGE_SENT] Status updated to SENT")

            // Send immediate cover traffic (1-2 decoys) to obscure the real message
            // Fire-and-forget: don't block the UI waiting for decoys
            val decoyCount = kotlin.random.Random.nextInt(1, 3) // 1-2 decoys
            Log.d(TAG, "🎭 [IMMEDIATE_COVER] Launching $decoyCount decoys in background")
            backgroundScope.launch {
                repeat(decoyCount) {
                    try {
                        sender.sendDecoyMessage()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️  Immediate decoy failed (non-critical): ${e.message}")
                    }
                }
            }
        }.onFailure { error ->
            // Update message status to FAILED
            Log.e(TAG, "❌ [SEND_FAILURE] Message send FAILED!")
            Log.e(TAG, "   📛 Error: ${error.message}")
            Log.e(TAG, "   📚 Stack trace:", error)
            updateMessageStatus(message.id, MessageStatus.FAILED)
            Log.e(TAG, "   📊 Status updated to FAILED")
            // TODO: Emit error event via EventBus
        }

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
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

        // Update conversation state for INSTANT-VOID adaptive mode (if enabled)
        conversationStateManager?.updateConversation(message.conversationId, message.timestamp)
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
        if (flow != null) {
            flow.value = flow.value.map {
                if (it.id == messageId) updatedMessage else it
            }
        }
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
     * Sync messages from the network using Supabase.
     *
     * Fetches messages from the user's mailbox and stores them locally.
     * Returns the number of new messages received.
     *
     * @param since Optional timestamp to fetch messages from (default: now)
     * @param force If true, bypasses debouncing (uses 10s emergency interval instead of 5min)
     * @param activeChat If true, uses 30s interval for polling while chat is open
     * @return Number of new messages received, or -1 if skipped due to debouncing
     */
    suspend fun syncMessages(since: Long? = null, force: Boolean = false, activeChat: Boolean = false): Int {
        // ✅ DEBOUNCE: Check if we should skip this sync
        if (syncDebouncer != null) {
            val shouldSync = syncDebouncer.shouldSync(force, activeChat)
            if (!shouldSync) {
                val remainingMs = syncDebouncer.getTimeUntilNextSync(force, activeChat)
                val remainingSec = remainingMs / 1000
                Log.d(TAG, "⏭️  [SYNC_SKIPPED] Debounced - wait ${remainingSec}s before next sync")
                return -1  // -1 indicates sync was skipped
            }
            // Record this sync attempt to prevent subsequent calls within debounce window
            syncDebouncer.recordSync()
        }

        // Check for fetcher (either Poisson Ghost or legacy)
        if (fetchMailboxClient == null && messageFetcher == null) {
            Log.w(TAG, "⚠️ [SYNC] No message fetcher available")
            return 0
        }

        val mailbox = mailboxDerivation ?: return 0

        // TODO: Get user's own identity seed
        // For now, we need to add a method to get the current user's identity
        val userIdentity = encryptionService?.getOwnIdentity()
        if (userIdentity == null) {
            Log.w(TAG, "⚠️ [SYNC] Cannot sync - user identity not available")
            return 0
        }

        var newMessageCount = 0
        val timestamp = since ?: System.currentTimeMillis()

        // ✅ FIX: Epoch for database queries is Unix timestamp in seconds
        // This is different from mailbox rotation epoch!
        val dbEpoch = timestamp / 1000

        // ✅ FIX: Use getActiveMailboxes() which properly handles rotation windows
        val activeMailboxes = mailbox.getActiveMailboxes(userIdentity.seed, timestamp)
        val mailboxHashes = activeMailboxes.map { it.hash }

        // DEBUG: Log full mailbox hashes for diagnosis
        Log.d(TAG, "🔍 [RECEIVER_MAILBOX] Checking ${activeMailboxes.size} active mailboxes:")
        Log.d(TAG, "🔍   My identity: ${userIdentity.threeWordIdentity}")
        Log.d(TAG, "🔍   My mailboxSeed (first 16 bytes): ${userIdentity.seed.take(16).joinToString("") { "%02x".format(it) }}")
        Log.d(TAG, "🔍   (This seed MUST match the mailboxSeed in my QR code)")
        activeMailboxes.forEachIndexed { index, mailbox ->
            val marker = if (mailbox.isPrimary) "PRIMARY" else "SECONDARY"
            Log.d(TAG, "🔍   [$marker] Mailbox $index: ${mailbox.hash} (epoch=${mailbox.epoch})")
        }
        Log.d(TAG, "🔍   Timestamp: $timestamp ms")
        Log.d(TAG, "🔍   DB Query Epoch: $dbEpoch sec")

        // Fetch messages using Poisson Ghost protocol (preferred) or legacy fetcher
        // ═══════════════════════════════════════════════════════════════════════
        // INSTANT DESTRUCTION: Messages are fetched but NOT deleted yet.
        // We only send signed ACKs (which trigger server deletion) AFTER
        // successfully storing messages locally. This prevents message loss.
        // ═══════════════════════════════════════════════════════════════════════
        val fetchedBatches: List<FetchedBatch> = if (fetchMailboxClient != null) {
            Log.d(TAG, "📥 [POISSON_GHOST] Using 4KB padded fetch protocol with instant destruction")
            // Poisson Ghost: Fetch mailboxes in PARALLEL with fetch-until-empty loop
            // Keep fetching each mailbox until we get a noise response (empty mailbox)
            coroutineScope {
                val deferredResults = mailboxHashes.map { mailboxHash ->
                    async {
                        fetchMailboxUntilEmpty(mailboxHash, userIdentity.seed, dbEpoch)
                    }
                }
                // Await all parallel fetches
                deferredResults.awaitAll()
            }
        } else {
            Log.d(TAG, "📥 [LEGACY] Using direct Postgrest fetch")
            // Legacy: Fetch all mailboxes in one call (variable response size)
            val result = messageFetcher!!.fetchMessages(userIdentity.seed, mailboxHashes, dbEpoch)
            val records = result.getOrElse { error ->
                Log.e(TAG, "❌ [SYNC_FAILED] ${error.message}", error)
                emptyList()
            }
            // Wrap in FetchedBatch with primary mailbox hash (no replay tracking in legacy mode)
            val primaryMailbox = activeMailboxes.firstOrNull { it.isPrimary }?.hash
                ?: activeMailboxes.firstOrNull()?.hash
                ?: mailboxHashes.firstOrNull()
                ?: ""
            listOf(FetchedBatch(records, emptyList(), primaryMailbox))
        }

        // ═══════════════════════════════════════════════════════════════════════
        // Process fetched messages and track for ACK
        // ═══════════════════════════════════════════════════════════════════════
        // Track successfully stored messages: (messageId, mailboxHash)
        val successfullyStored = mutableListOf<Pair<String, String>>()
        // Track replay messages that need ACKs (already stored locally, need server cleanup)
        val replayNeedingAck = mutableListOf<Pair<String, String>>()
        val processedIds = mutableListOf<String>()

        for (batch in fetchedBatches) {
            // Process new messages
            for (record in batch.records) {
                val message = parseSupabaseMessage(record)
                if (message != null) {
                    // Store message locally FIRST (critical - must succeed before ACK)
                    receiveMessage(message)
                    newMessageCount++
                    processedIds.add(record.id)

                    // Track for ACK only after successful local storage
                    successfullyStored.add(record.id to batch.mailboxHash)
                } else {
                    Log.w(TAG, "⚠️ [SYNC] Failed to parse message ${record.id}")
                    // Do NOT add to successfullyStored - we don't ACK failed messages
                    // They will be retried on next sync and eventually expire via TTL
                }
            }

            // Track replay messages for ACK cleanup (they're already stored locally)
            for (record in batch.replayRecords) {
                replayNeedingAck.add(record.id to batch.mailboxHash)
            }
        }

        if (processedIds.isNotEmpty()) {
            Log.d(TAG, "📥 [SYNC] Processed ${processedIds.size} new messages successfully")
        }
        if (replayNeedingAck.isNotEmpty()) {
            Log.d(TAG, "🔄 [SYNC] Found ${replayNeedingAck.size} replay messages needing server cleanup")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // INSTANT DESTRUCTION: Send signed ACKs to delete messages from server
        // ═══════════════════════════════════════════════════════════════════════
        // ACKs are sent for:
        // 1. New messages AFTER they are stored locally (ensures no message loss)
        // 2. Replay messages (already stored locally, need server cleanup)
        val allNeedingAck = successfullyStored + replayNeedingAck

        if (allNeedingAck.isNotEmpty() && deliveryAckService != null) {
            val newCount = successfullyStored.size
            val replayCount = replayNeedingAck.size
            Log.d(TAG, "🗑️ [INSTANT_DESTRUCTION] Sending ${allNeedingAck.size} delivery ACKs ($newCount new, $replayCount replay cleanup)...")

            val ackResult = deliveryAckService.sendBatchAcks(userIdentity.seed, allNeedingAck)
            ackResult.onSuccess { count ->
                Log.d(TAG, "🗑️ [INSTANT_DESTRUCTION] $count/${allNeedingAck.size} messages deleted from server")
            }.onFailure { error ->
                // ACK failed - messages will stay on server until TTL expires (24h)
                // SeenMessageTracker will filter them on next sync (no duplicates)
                Log.w(TAG, "⚠️ [ACK_FAILED] Server deletion failed: ${error.message}")
                Log.w(TAG, "⚠️ [ACK_FAILED] Messages will expire via 24h TTL")
            }
        } else if (successfullyStored.isNotEmpty() && deliveryAckService == null) {
            // Fallback: Use legacy delete if no ACK service (maintains backward compatibility)
            Log.d(TAG, "🗑️ [LEGACY_DELETE] ACK service not available, using legacy delete")
            val primaryMailbox = activeMailboxes.firstOrNull { it.isPrimary }?.hash
                ?: activeMailboxes.firstOrNull()?.hash
                ?: mailboxHashes.firstOrNull()

            if (primaryMailbox != null) {
                messageFetcher?.deleteMessages(userIdentity.seed, processedIds, primaryMailbox)
            }
        }

        return newMessageCount
    }

    /**
     * Result of fetching messages from a mailbox.
     * Includes the mailbox hash for ACK purposes.
     *
     * @param records New messages to be processed and stored locally
     * @param replayRecords Messages already stored locally that need ACKs to delete from server
     * @param mailboxHash The mailbox hash (needed for ACK signing)
     */
    private data class FetchedBatch(
        val records: List<MessageRecord>,
        val replayRecords: List<MessageRecord>,
        val mailboxHash: String
    )

    /**
     * Fetch all messages from a single mailbox until empty.
     * Used by parallel fetch to isolate each mailbox's fetch loop.
     *
     * INSTANT DESTRUCTION: Messages are NOT deleted here anymore.
     * Instead, we return the records for processing, and ACKs are sent
     * AFTER successful local storage in syncMessages().
     *
     * REPLAY HANDLING: Messages already seen (stored locally) are tracked
     * separately so ACKs can be sent to delete them from the server.
     *
     * @param mailboxHash The mailbox hash to fetch from
     * @param identitySeed User's identity seed for token generation
     * @param dbEpoch Database query epoch
     * @return FetchedBatch containing new records, replay records, and mailbox hash
     */
    private suspend fun fetchMailboxUntilEmpty(
        mailboxHash: String,
        identitySeed: ByteArray,
        dbEpoch: Long
    ): FetchedBatch {
        val records = mutableListOf<MessageRecord>()
        val replayRecords = mutableListOf<MessageRecord>()
        var fetchCount = 0
        var keepFetching = true
        val MAX_BATCHES = 100 // Safety limit to prevent infinite loops

        while (keepFetching && fetchCount < MAX_BATCHES) {
            fetchCount++
            fetchMailboxClient?.fetchMessages(identitySeed, mailboxHash, dbEpoch)
                ?.onSuccess { mailboxRecords ->
                    if (mailboxRecords.isNotEmpty()) {
                        // ═══════════════════════════════════════════════════════════
                        // REPLAY ATTACK PROTECTION: Separate new vs already-seen messages
                        // ═══════════════════════════════════════════════════════════
                        var batchNewCount = 0
                        var batchReplayCount = 0

                        if (seenMessageTracker != null) {
                            for (record in mailboxRecords) {
                                if (seenMessageTracker.markSeenIfNew(record.id)) {
                                    // New message - process it
                                    records.add(record)
                                    batchNewCount++
                                } else {
                                    // Replay - already stored locally, but needs ACK to delete from server
                                    replayRecords.add(record)
                                    batchReplayCount++
                                }
                            }
                        } else {
                            // No tracker = treat all as new
                            records.addAll(mailboxRecords)
                            batchNewCount = mailboxRecords.size
                        }

                        if (batchNewCount == 0 && batchReplayCount > 0) {
                            // All messages were replays - mailbox is "logically empty" for processing
                            // but we captured the replays for ACK cleanup
                            Log.d(TAG, "🔄 [REPLAY_CLEANUP] All $batchReplayCount messages are replays - need ACK cleanup")
                            Log.d(TAG, "✓ [FETCH_COMPLETE] Mailbox ${mailboxHash.take(8)}... logically empty (only replays)")
                            keepFetching = false  // STOP - no new messages to find behind these
                        } else if (batchReplayCount > 0) {
                            Log.d(TAG, "📥 [FETCH_LOOP] Batch $fetchCount: $batchNewCount new, $batchReplayCount replays from ${mailboxHash.take(8)}...")
                        } else {
                            Log.d(TAG, "📥 [FETCH_LOOP] Batch $fetchCount: $batchNewCount new messages from ${mailboxHash.take(8)}...")
                        }

                        // ═══════════════════════════════════════════════════════════
                        // INSTANT DESTRUCTION: Do NOT delete messages here!
                        // Messages stay on server until we send signed ACK after
                        // successful local storage. This prevents message loss if
                        // the app crashes between fetch and local storage.
                        //
                        // ACKs are sent in syncMessages() for:
                        // 1. New messages after receiveMessage() succeeds
                        // 2. Replay messages (already stored, need server cleanup)
                        // ═══════════════════════════════════════════════════════════

                    } else {
                        // Empty response (noise) - mailbox is empty
                        Log.d(TAG, "✓ [FETCH_COMPLETE] Mailbox ${mailboxHash.take(8)}... empty after $fetchCount batch(es)")
                        keepFetching = false
                    }
                }
                ?.onFailure { error ->
                    Log.e(TAG, "❌ [SYNC] Failed to fetch mailbox ${mailboxHash.take(8)}...: ${error.message}")
                    keepFetching = false
                }
        }

        if (fetchCount >= MAX_BATCHES) {
            Log.w(TAG, "⚠️ [FETCH_LIMIT] Hit safety limit of $MAX_BATCHES batches for mailbox ${mailboxHash.take(8)}...")
        }

        return FetchedBatch(records, replayRecords, mailboxHash)
    }

    /**
     * Parse a Supabase message record into a Message domain object.
     * Decrypts the message content and extracts sender ID from sealed sender header.
     */
    private suspend fun parseSupabaseMessage(record: MessageRecord): Message? {
        return try {
            // Decode base64 ciphertext
            val encryptedPayload = Base64.decode(record.ciphertext, Base64.NO_WRAP)

            if (encryptionService == null) {
                Log.w(TAG, "⚠️  [NO_ENCRYPTION] Receiving unencrypted message (testing mode)")
                // Fallback: no encryption (for testing only)
                val plaintext = encryptedPayload.decodeToString()
                val message = Message(
                    id = record.id,
                    conversationId = "unknown",
                    senderId = "unknown",
                    recipientId = "me",
                    content = MessageContent.Text(plaintext),
                    direction = MessageDirection.INCOMING,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.DELIVERED,
                    deliveredAt = System.currentTimeMillis(),
                    encryptedPayload = encryptedPayload
                )
                return message
            }

            // ✅ NEW: Use sealed sender decryption (no senderId needed upfront)
            val decrypted = encryptionService.decryptReceivedMessage(encryptedPayload)
            if (decrypted == null) {
                Log.e(TAG, "❌ [DECRYPT_FAILED] Failed to decrypt message ${record.id}")
                return null
            }

            // ✅ Extract sender ID from sealed sender header (this is the public key hex)
            val senderPublicKeyHex = decrypted.senderId
            val plaintext = decrypted.content
            val messageTimestamp = decrypted.timestamp

            Log.d(TAG, "✅ [SEALED_SENDER_PARSED] senderPublicKeyHex=${senderPublicKeyHex.take(16)}..., timestamp=$messageTimestamp")

            // ✅ Look up contact by public key hex to get their UUID
            val contactId = publicKeyToContactId?.invoke(senderPublicKeyHex)
            if (contactId == null) {
                Log.e(TAG, "❌ [CONTACT_NOT_FOUND] Cannot find contact with public key: ${senderPublicKeyHex.take(16)}...")
                Log.e(TAG, "⚠️  [MESSAGE_IGNORED] Message from unknown sender will be ignored")
                return null
            }

            Log.d(TAG, "✓ [CONTACT_MATCHED] publicKey=${senderPublicKeyHex.take(16)}... -> contactId=$contactId")

            // ═══════════════════════════════════════════════════════════════
            // MUTUAL VERIFICATION: Process handshake and auto-verify
            // Receiving any message from a contact proves they have our QR.
            // ═══════════════════════════════════════════════════════════════

            // Check if this is a handshake message (invisible to users)
            if (plaintext.startsWith(HANDSHAKE_PREFIX)) {
                val handshakeType = plaintext.removePrefix(HANDSHAKE_PREFIX)
                Log.d(TAG, "🤝 [HANDSHAKE] Received handshake '$handshakeType' from contact $contactId")

                // Mark sender as mutually verified (they clearly have our QR)
                val wasAlreadyVerified = isContactMutuallyVerified?.invoke(contactId) ?: true
                if (!wasAlreadyVerified) {
                    markContactMutuallyVerified?.invoke(contactId)
                    Log.d(TAG, "✅ [HANDSHAKE] Contact $contactId marked as mutually verified")

                    // Respond with pong if we received a ping
                    if (handshakeType == "ping") {
                        sendHandshakeResponse(contactId)
                    }

                    // Flush any pending messages now that contact is verified
                    flushPendingMessages(contactId)
                }

                // Don't store handshake messages - they're invisible to users
                return null
            }

            // For regular messages: auto-verify sender if not yet verified
            val isVerified = isContactMutuallyVerified?.invoke(contactId) ?: true
            if (!isVerified) {
                markContactMutuallyVerified?.invoke(contactId)
                Log.d(TAG, "✅ [AUTO_VERIFY] Contact $contactId verified via received message")

                // Send pong so they know we verified them too
                sendHandshakeResponse(contactId)

                // Flush any pending messages we had queued for them
                flushPendingMessages(contactId)
            }

            // Create message with contact UUID (not public key hex!)
            val message = Message(
                id = record.id,
                conversationId = contactId,  // ✅ Use contact UUID for conversation
                senderId = contactId,        // ✅ Use contact UUID for sender
                recipientId = "me",
                content = MessageContent.Text(plaintext),
                direction = MessageDirection.INCOMING,
                timestamp = messageTimestamp,  // ✅ Use timestamp from header
                status = MessageStatus.DELIVERED,
                deliveredAt = System.currentTimeMillis(),
                encryptedPayload = encryptedPayload
            )

            Log.d(TAG, "✓ [MESSAGE_RECEIVED] messageId=${message.id}, from=$contactId, content=\"$plaintext\"")
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

    // ═══════════════════════════════════════════════════════════════
    // Mutual Verification Handshake
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if a contact is mutually verified. If not, initiate handshake.
     * Called when opening a chat to ensure verification status is current.
     *
     * @return true if contact is mutually verified, false if pending
     */
    suspend fun ensureMutualVerification(contactId: String): Boolean {
        val isVerified = isContactMutuallyVerified?.invoke(contactId) ?: true
        if (!isVerified) {
            Log.d(TAG, "🤝 [VERIFY_CHECK] Contact $contactId not verified, sending handshake ping")
            sendHandshakePing(contactId)
            return false
        }
        return true
    }

    /**
     * Send a handshake ping to initiate mutual verification.
     * Bypasses local storage - handshake messages are invisible to users.
     */
    private suspend fun sendHandshakePing(contactId: String) {
        if (messageSender == null || encryptionService == null) return

        Log.d(TAG, "🤝 [HANDSHAKE_PING] Sending ping to contact $contactId")

        val message = Message(
            conversationId = contactId,
            senderId = "me",
            recipientId = contactId,
            content = MessageContent.Handshake(type = "ping"),
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENDING
        )

        sendMessageViaNetwork(message, messageSender)
    }

    /**
     * Send a handshake pong in response to a ping.
     * Confirms mutual verification to the other party.
     */
    private suspend fun sendHandshakeResponse(contactId: String) {
        if (messageSender == null || encryptionService == null) return

        Log.d(TAG, "🤝 [HANDSHAKE_PONG] Sending pong to contact $contactId")

        val message = Message(
            conversationId = contactId,
            senderId = "me",
            recipientId = contactId,
            content = MessageContent.Handshake(type = "pong"),
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENDING
        )

        sendMessageViaNetwork(message, messageSender)
    }

    /**
     * Flush pending messages after mutual verification is confirmed.
     * Re-sends messages that were queued with PENDING_VERIFICATION status.
     */
    private suspend fun flushPendingMessages(contactId: String) {
        if (messageSender == null) return

        val messageIds = getMessageIds(contactId)
        val pendingMessages = messageIds.mapNotNull { id ->
            getMessage(id)
        }.filter { it.status == MessageStatus.PENDING_VERIFICATION }

        if (pendingMessages.isEmpty()) return

        Log.d(TAG, "📤 [FLUSH_PENDING] Flushing ${pendingMessages.size} pending messages for contact $contactId")

        for (message in pendingMessages) {
            // Update timestamp and status for freshness
            val updated = message.copy(
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING
            )

            // Update local storage
            val messageKey = "$KEY_PREFIX_MESSAGE${message.id}"
            val messageJson = json.encodeToString(updated)
            storage.put(messageKey, messageJson.toByteArray())

            // Update in-memory cache
            val flow = _messagesCache[updated.conversationId]
            flow?.value = flow?.value?.map {
                if (it.id == message.id) updated else it
            } ?: emptyList()

            // Send via network
            sendMessageViaNetwork(updated, messageSender)
        }
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
