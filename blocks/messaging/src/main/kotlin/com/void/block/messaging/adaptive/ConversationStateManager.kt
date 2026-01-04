package com.void.block.messaging.adaptive

import android.util.Log
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages conversation states for adaptive INSTANT-VOID protocol.
 *
 * Responsibilities:
 * - Track last message timestamp for each conversation
 * - Determine current mode (ACTIVE/SEMI_ACTIVE/DORMANT) for each conversation
 * - Detect mode transitions
 * - Persist state across app restarts
 * - Provide global mode (most active conversation determines overall system behavior)
 *
 * Thread-safe: Uses mutex for concurrent access protection.
 */
class ConversationStateManager(
    private val storage: SecureStorage
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val states = mutableMapOf<String, ConversationState>()

    companion object {
        private const val TAG = "ConversationStateManager"
        private const val STORAGE_KEY_PREFIX = "conversation_state."
        private const val STORAGE_KEY_ALL_IDS = "conversation_state.all_ids"
    }

    /**
     * Initialize the state manager by loading persisted states.
     */
    suspend fun initialize() {
        mutex.withLock {
            val conversationIds = loadConversationIds()
            Log.d(TAG, "Initializing with ${conversationIds.size} conversations")

            conversationIds.forEach { id ->
                val state = loadState(id)
                if (state != null) {
                    states[id] = state
                }
            }

            Log.d(TAG, "Loaded ${states.size} conversation states")
        }
    }

    /**
     * Update conversation state when a new message is sent or received.
     *
     * @param conversationId The conversation ID
     * @param timestamp The message timestamp
     */
    suspend fun updateConversation(conversationId: String, timestamp: Long = System.currentTimeMillis()) {
        mutex.withLock {
            val currentState = states[conversationId]
            val newState = if (currentState != null) {
                currentState.copy(
                    lastMessageTimestamp = timestamp,
                    messageCount = currentState.messageCount + 1,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            } else {
                ConversationState(
                    conversationId = conversationId,
                    lastMessageTimestamp = timestamp,
                    messageCount = 1,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            }

            // Check if mode changed
            val oldMode = currentState?.currentMode ?: ConversationMode.DORMANT
            val newMode = newState.determineMode()

            if (oldMode != newMode) {
                Log.d(TAG, "[$conversationId] Mode transition: $oldMode → $newMode")
            }

            states[conversationId] = newState.copy(currentMode = newMode)
            persistState(conversationId, states[conversationId]!!)
        }
    }

    /**
     * Get the current mode for a specific conversation.
     *
     * @param conversationId The conversation ID
     * @return The current mode, or DORMANT if conversation not found
     */
    suspend fun getMode(conversationId: String): ConversationMode {
        return mutex.withLock {
            val state = states[conversationId]
            if (state != null) {
                // Re-evaluate mode based on current time
                val currentMode = state.determineMode()
                if (currentMode != state.currentMode) {
                    // Update mode if changed
                    states[conversationId] = state.copy(currentMode = currentMode)
                    persistState(conversationId, states[conversationId]!!)
                    Log.d(TAG, "[$conversationId] Mode auto-updated: ${state.currentMode} → $currentMode")
                }
                currentMode
            } else {
                ConversationMode.DORMANT
            }
        }
    }

    /**
     * Get the global mode (most active conversation determines system behavior).
     *
     * This is used to decide the overall polling strategy when no specific
     * conversation is targeted (e.g., background sync).
     *
     * Priority: ACTIVE > SEMI_ACTIVE > DORMANT
     *
     * @return The most active mode across all conversations
     */
    suspend fun getGlobalMode(): ConversationMode {
        return mutex.withLock {
            // Update all modes first
            states.values.forEach { state ->
                val currentMode = state.determineMode()
                if (currentMode != state.currentMode) {
                    states[state.conversationId] = state.copy(currentMode = currentMode)
                    persistState(state.conversationId, states[state.conversationId]!!)
                }
            }

            // Find most active mode
            val modes = states.values.map { it.currentMode }

            when {
                modes.contains(ConversationMode.ACTIVE) -> {
                    Log.d(TAG, "[GLOBAL_MODE] ACTIVE (${modes.count { it == ConversationMode.ACTIVE }} active conversations)")
                    ConversationMode.ACTIVE
                }
                modes.contains(ConversationMode.SEMI_ACTIVE) -> {
                    Log.d(TAG, "[GLOBAL_MODE] SEMI_ACTIVE (${modes.count { it == ConversationMode.SEMI_ACTIVE }} semi-active conversations)")
                    ConversationMode.SEMI_ACTIVE
                }
                else -> {
                    Log.d(TAG, "[GLOBAL_MODE] DORMANT (all conversations dormant)")
                    ConversationMode.DORMANT
                }
            }
        }
    }

    /**
     * Get conversation state for debugging/monitoring.
     *
     * @param conversationId The conversation ID
     * @return The conversation state, or null if not found
     */
    suspend fun getState(conversationId: String): ConversationState? {
        return mutex.withLock {
            states[conversationId]
        }
    }

    /**
     * Get all conversation states (for debugging).
     */
    suspend fun getAllStates(): List<ConversationState> {
        return mutex.withLock {
            states.values.toList()
        }
    }

    /**
     * Remove a conversation state (when conversation is deleted).
     *
     * @param conversationId The conversation ID to remove
     */
    suspend fun removeConversation(conversationId: String) {
        mutex.withLock {
            states.remove(conversationId)
            deleteState(conversationId)
            Log.d(TAG, "[$conversationId] State removed")
        }
    }

    /**
     * Clear all conversation states (for panic wipe).
     */
    suspend fun clearAll() {
        mutex.withLock {
            val conversationIds = states.keys.toList()
            states.clear()
            conversationIds.forEach { id ->
                deleteState(id)
            }
            storage.delete(STORAGE_KEY_ALL_IDS)
            Log.d(TAG, "All conversation states cleared")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════════

    private suspend fun persistState(conversationId: String, state: ConversationState) {
        try {
            val key = "$STORAGE_KEY_PREFIX$conversationId"
            val stateJson = json.encodeToString(state)
            storage.put(key, stateJson.toByteArray())

            // Update conversation IDs list
            val ids = loadConversationIds().toMutableSet()
            ids.add(conversationId)
            saveConversationIds(ids)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist state for $conversationId: ${e.message}")
        }
    }

    private suspend fun loadState(conversationId: String): ConversationState? {
        return try {
            val key = "$STORAGE_KEY_PREFIX$conversationId"
            val bytes = storage.get(key) ?: return null
            json.decodeFromString<ConversationState>(bytes.decodeToString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load state for $conversationId: ${e.message}")
            null
        }
    }

    private suspend fun deleteState(conversationId: String) {
        try {
            val key = "$STORAGE_KEY_PREFIX$conversationId"
            storage.delete(key)

            // Update conversation IDs list
            val ids = loadConversationIds().toMutableSet()
            ids.remove(conversationId)
            saveConversationIds(ids)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete state for $conversationId: ${e.message}")
        }
    }

    private suspend fun loadConversationIds(): Set<String> {
        return try {
            val bytes = storage.get(STORAGE_KEY_ALL_IDS) ?: return emptySet()
            json.decodeFromString<Set<String>>(bytes.decodeToString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation IDs: ${e.message}")
            emptySet()
        }
    }

    private suspend fun saveConversationIds(ids: Set<String>) {
        try {
            val idsJson = json.encodeToString(ids)
            storage.put(STORAGE_KEY_ALL_IDS, idsJson.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save conversation IDs: ${e.message}")
        }
    }
}
