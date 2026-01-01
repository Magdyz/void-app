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
     * Triggers background sync worker to fetch new messages.
     */
    fun syncMessages() {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true
                // Trigger immediate sync from server
                syncScheduler.triggerImmediateSync()
                // The sync will happen in background, and messages will auto-update
                // through the repository's Flow

                // Keep refreshing indicator visible for a short time
                // to give user feedback that sync was triggered
                kotlinx.coroutines.delay(1000)
            } catch (e: Exception) {
                // Sync error - non-critical, periodic sync will retry
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
