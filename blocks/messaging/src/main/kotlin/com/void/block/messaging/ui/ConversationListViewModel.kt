package com.void.block.messaging.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.messaging.data.MessageRepository
import com.void.block.messaging.domain.Conversation
import com.void.slate.network.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for conversation list screen.
 * Manages list of conversations and their state.
 */
class ConversationListViewModel(
    private val messageRepository: MessageRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _state = MutableStateFlow<ConversationListState>(ConversationListState.Loading)
    val state: StateFlow<ConversationListState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadConversations()
    }

    /**
     * Load all conversations.
     */
    fun loadConversations() {
        viewModelScope.launch {
            _state.value = ConversationListState.Loading

            try {
                // Load conversations from repository
                messageRepository.loadConversations()

                // Observe conversations flow
                messageRepository.conversations.collect { conversations ->
                    _state.value = if (conversations.isEmpty()) {
                        ConversationListState.Empty
                    } else {
                        ConversationListState.Success(conversations)
                    }
                }
            } catch (e: Exception) {
                _state.value = ConversationListState.Error(e.message ?: "Failed to load conversations")
            }
        }
    }

    /**
     * Delete a conversation.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                messageRepository.deleteConversation(conversationId)
            } catch (e: Exception) {
                // Handle error - could emit a separate error event
            }
        }
    }

    /**
     * Mark conversation as read.
     */
    fun markAsRead(conversationId: String) {
        viewModelScope.launch {
            try {
                messageRepository.markConversationAsRead(conversationId)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Refresh conversations (pull to refresh).
     */
    fun refresh() {
        loadConversations()
    }

    /**
     * Sync messages from server immediately.
     * Triggered by pull-to-refresh gesture.
     *
     * SECURITY: Uses normal 5-minute debounce to prevent spam.
     * User can pull-to-refresh but actual sync only happens every 5 minutes.
     * This prevents anxious users from burning through API limits.
     */
    fun syncMessages() {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true

                // Use normal debounce (5 minutes) - not force
                // Pull-to-refresh is easy to spam, so we don't bypass debounce
                val count = messageRepository.syncMessages(force = false)

                if (count > 0) {
                    android.util.Log.d("ConversationList", "✅ Pull-to-refresh: Synced $count new messages")
                } else if (count == -1) {
                    android.util.Log.d("ConversationList", "⏭️  Pull-to-refresh: Debounced - sync will happen automatically in background")
                } else {
                    android.util.Log.d("ConversationList", "📭 Pull-to-refresh: No new messages")
                }

                // Keep refreshing indicator visible for a short time
                // Even if debounced, show user we acknowledged their action
                kotlinx.coroutines.delay(500)
            } catch (e: Exception) {
                android.util.Log.e("ConversationList", "❌ Pull-to-refresh failed: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}

/**
 * UI state for conversation list.
 */
sealed class ConversationListState {
    object Loading : ConversationListState()
    object Empty : ConversationListState()
    data class Success(val conversations: List<Conversation>) : ConversationListState()
    data class Error(val message: String) : ConversationListState()
}
