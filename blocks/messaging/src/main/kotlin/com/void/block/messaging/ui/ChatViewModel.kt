package com.void.block.messaging.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.messaging.data.MessageRepository
import com.void.block.messaging.domain.Message
import com.void.block.messaging.domain.MessageContent
import com.void.block.messaging.domain.MessageDirection
import com.void.block.messaging.domain.MessageDraft
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * ViewModel for chat screen.
 * Manages messages for a specific conversation.
 */
class ChatViewModel(
    private val conversationId: String,
    private val contactId: String,
    private val messageRepository: MessageRepository
) : ViewModel() {

    companion object {
        private const val TAG = "VOID_SECURITY"
    }

    private val _state = MutableStateFlow<ChatState>(ChatState.Loading)
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    init {
        loadMessages()
        loadDraft()
        startMessagePolling()
    }

    /**
     * Start polling for new messages.
     *
     * SECURITY NOTE: Polling interval is intentionally long (30s) to reduce metadata leakage.
     * With debouncing enabled (5min), most poll attempts will be skipped anyway.
     * Real-time updates rely on FCM pushes + Poisson Ghost heartbeats.
     */
    private fun startMessagePolling() {
        viewModelScope.launch {
            Log.d(TAG, "🔄 [POLLING_START] Message polling started (30s interval)")

            // ✅ FIX: Wait before first poll to avoid race with initial force sync
            delay(30_000)

            while (true) {
                try {
                    // Sync messages from network (will be debounced if called too frequently)
                    val count = messageRepository.syncMessages()
                    if (count == -1) {
                        // Debounced - sync was skipped
                        Log.d(TAG, "🔄 [POLLING] Sync debounced (too soon since last sync)")
                    } else if (count > 0) {
                        Log.d(TAG, "🔄 [POLLING] Synced $count new messages")
                    } else {
                        Log.d(TAG, "🔄 [POLLING] No new messages")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [POLLING_ERROR] ${e.message}", e)
                }
                delay(30_000) // Poll every 30 seconds (down from 3s - debouncer will handle actual rate limiting)
            }
        }
    }

    /**
     * Load messages for this conversation.
     */
    private fun loadMessages() {
        viewModelScope.launch {
            _state.value = ChatState.Loading

            try {
                // ✅ FIX: Force an immediate sync when user opens chat (bypasses 5-min debounce)
                // Uses 10-second emergency debounce instead, so user gets fresh messages
                Log.d(TAG, "🔄 [CHAT_OPEN] User opened chat - forcing immediate sync")
                val syncCount = messageRepository.syncMessages(force = true)
                if (syncCount > 0) {
                    Log.d(TAG, "✅ [CHAT_OPEN] Fetched $syncCount new messages")
                } else if (syncCount == -1) {
                    Log.d(TAG, "⏭️  [CHAT_OPEN] Sync debounced (used emergency 10s interval)")
                }

                // Load messages from repository
                messageRepository.loadMessages(conversationId)

                // Mark conversation as read
                messageRepository.markConversationAsRead(conversationId)

                // Observe messages flow
                messageRepository.getMessagesFlow(conversationId).collect { messages ->
                    _state.value = ChatState.Success(messages)
                }
            } catch (e: Exception) {
                _state.value = ChatState.Error(e.message ?: "Failed to load messages")
            }
        }
    }

    /**
     * Load draft message if exists.
     */
    private fun loadDraft() {
        viewModelScope.launch {
            val draft = messageRepository.getDraft(conversationId)
            if (draft != null) {
                _messageText.value = draft.text
            }
        }
    }

    /**
     * Update message text being typed.
     */
    fun onMessageTextChange(text: String) {
        _messageText.value = text

        // Save draft
        viewModelScope.launch {
            if (text.isNotBlank()) {
                messageRepository.saveDraft(
                    MessageDraft(
                        conversationId = conversationId,
                        text = text
                    )
                )
            } else {
                messageRepository.deleteDraft(conversationId)
            }
        }

        // Update typing indicator
        // In real app, would emit typing events via EventBus
        _isTyping.value = text.isNotBlank()
    }

    /**
     * Send the current message.
     */
    fun sendMessage() {
        val text = _messageText.value.trim()
        if (text.isBlank()) return

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "🚀 [SEND_START] User initiated message send")
        Log.d(TAG, "   📝 Text: \"$text\"")
        Log.d(TAG, "   👤 ContactID: $contactId")
        Log.d(TAG, "   💬 ConversationID: $conversationId")

        viewModelScope.launch {
            try {
                // Create message
                val message = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderId = "me", // Current user ID
                    recipientId = contactId,
                    content = MessageContent.Text(text),
                    direction = MessageDirection.OUTGOING
                )

                Log.d(TAG, "✅ [MESSAGE_CREATED] Message object created")
                Log.d(TAG, "   🆔 MessageID: ${message.id}")
                Log.d(TAG, "   ⏰ Timestamp: ${message.timestamp}")
                Log.d(TAG, "   📊 Status: ${message.status}")

                // Send via repository
                Log.d(TAG, "📤 [CALLING_REPOSITORY] Calling messageRepository.sendMessage()")
                messageRepository.sendMessage(message)
                Log.d(TAG, "✅ [REPOSITORY_RETURNED] messageRepository.sendMessage() returned")

                // Clear input
                _messageText.value = ""
                messageRepository.deleteDraft(conversationId)

                // In real app, would:
                // 1. Encrypt message with MessageEncryption
                // 2. Send encrypted message via network
                // 3. Update message status when delivered/read

            } catch (e: Exception) {
                // Handle error - could show a snackbar
            }
        }
    }

    /**
     * Delete a message.
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.deleteMessage(messageId)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Mark message as read (for incoming messages).
     */
    fun markMessageAsRead(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.updateMessageStatus(
                    messageId,
                    com.void.block.messaging.domain.MessageStatus.READ
                )
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}

/**
 * UI state for chat screen.
 */
sealed class ChatState {
    object Loading : ChatState()
    data class Success(val messages: List<Message>) : ChatState()
    data class Error(val message: String) : ChatState()
}
