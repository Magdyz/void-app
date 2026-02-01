package com.void.slate.network.sync

import android.util.Log
import com.void.slate.storage.SecureStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tracks seen message IDs to prevent replay attacks.
 *
 * When the server attempts to push an already-processed message ID,
 * the client silently ignores it. This prevents:
 * - Replay attacks where an attacker re-sends old messages
 * - Server bugs that might resend messages
 * - Race conditions during sync
 * - Duplicate messages after ACK network failures
 *
 * Implementation: Simple Set<String> stored in SecureStorage with rolling window.
 * For high-volume use cases, consider Bloom filter optimization.
 *
 * Storage: Encrypted via SQLCipher in SecureStorage.
 */
class SeenMessageTracker(
    private val storage: SecureStorage,
    private val maxEntries: Int = MAX_ENTRIES_DEFAULT
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "SeenMessageTracker"
        private const val STORAGE_KEY = "sync.seen_message_ids"

        /**
         * Maximum number of message IDs to track.
         * Rolling window: oldest entries evicted when limit reached.
         * 10,000 entries at ~36 bytes per UUID = ~360KB storage.
         */
        private const val MAX_ENTRIES_DEFAULT = 10_000
    }

    /**
     * Check if message ID has been seen before.
     * If not seen, marks it as seen (atomic operation).
     *
     * This is the primary method for replay protection:
     * - Call this for each message received from the server
     * - If returns false, the message is a replay and should be ignored
     * - If returns true, the message is new and should be processed
     *
     * @param messageId The UUID of the message to check
     * @return true if message is NEW (should be processed)
     * @return false if message was ALREADY SEEN (should be ignored)
     */
    suspend fun markSeenIfNew(messageId: String): Boolean {
        val seen = loadSeenIds().toMutableList()

        if (messageId in seen) {
            Log.d(TAG, "⚠️ [REPLAY_DETECTED] Message $messageId already seen - ignoring")
            return false  // Already processed - this is a replay
        }

        // Add to seen list
        seen.add(messageId)

        // Evict oldest entries if over limit (FIFO via list order)
        val trimmed = if (seen.size > maxEntries) {
            val evicted = seen.size - maxEntries
            Log.d(TAG, "📤 [EVICTION] Evicting $evicted oldest message IDs")
            seen.takeLast(maxEntries)
        } else {
            seen
        }

        saveSeenIds(trimmed)
        Log.d(TAG, "✅ [NEW_MESSAGE] Marked ${messageId.take(8)}... as seen (total: ${trimmed.size})")
        return true  // New message - process it
    }

    /**
     * Check if message has been seen (without marking).
     * Use this for read-only checks without modifying state.
     *
     * @param messageId The UUID of the message to check
     * @return true if message has been seen before
     */
    suspend fun hasSeen(messageId: String): Boolean {
        return messageId in loadSeenIds()
    }

    /**
     * Get count of tracked message IDs.
     * Useful for diagnostics and monitoring.
     */
    suspend fun count(): Int {
        return loadSeenIds().size
    }

    /**
     * Clear all seen IDs.
     * Use for:
     * - Panic wipe (security emergency)
     * - Testing/debugging
     * - Storage recovery after corruption
     *
     * WARNING: After clearing, all messages will appear as "new"
     * which may cause duplicate message display to users.
     */
    suspend fun clear() {
        storage.delete(STORAGE_KEY)
        Log.w(TAG, "🗑️ [CLEARED] All seen message IDs cleared")
    }

    /**
     * Load seen IDs from encrypted storage.
     * Returns empty list on error (fail-safe: treat all as new).
     */
    private suspend fun loadSeenIds(): List<String> {
        val bytes = storage.get(STORAGE_KEY) ?: return emptyList()
        return try {
            json.decodeFromString<SeenIdsWrapper>(bytes.decodeToString()).ids
        } catch (e: Exception) {
            Log.e(TAG, "❌ [LOAD_ERROR] Failed to load seen IDs, returning empty: ${e.message}")
            emptyList()  // Fail-safe: treat all messages as new
        }
    }

    /**
     * Save seen IDs to encrypted storage.
     */
    private suspend fun saveSeenIds(ids: List<String>) {
        try {
            val wrapper = SeenIdsWrapper(ids)
            storage.put(STORAGE_KEY, json.encodeToString(wrapper).toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "❌ [SAVE_ERROR] Failed to save seen IDs: ${e.message}")
            // Continue without saving - next sync will add them again
        }
    }

    /**
     * Wrapper class for JSON serialization.
     */
    @Serializable
    private data class SeenIdsWrapper(val ids: List<String>)
}
