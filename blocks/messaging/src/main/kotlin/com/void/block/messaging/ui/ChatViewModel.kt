package com.void.block.messaging.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.messaging.data.MessageRepository
import com.void.block.messaging.domain.Message
import com.void.block.messaging.domain.MessageContent
import com.void.block.messaging.domain.MessageDirection
import com.void.block.messaging.domain.MessageDraft
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private val _state = MutableStateFlow<ChatState>(ChatState.Loading)
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    init {
        loadMessages()
        loadDraft()
    }

    /**
     * Load messages for this conversation.
     */
    private fun loadMessages() {
        viewModelScope.launch {
            _state.value = ChatState.Loading

            try {
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

                // Send via repository
                messageRepository.sendMessage(message)

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
