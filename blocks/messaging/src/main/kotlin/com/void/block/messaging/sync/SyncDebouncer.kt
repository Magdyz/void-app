package com.void.block.messaging.sync

import android.util.Log
import com.void.slate.storage.SecureStorage

/**
 * Debouncer for message synchronization to prevent excessive API calls.
 *
 * Ensures that syncs are not performed more frequently than the configured debounce interval,
 * even if the user spams refresh or multiple FCM pushes arrive in quick succession.
 *
 * This helps:
 * - Reduce bandwidth usage
 * - Prevent Edge Function call exhaustion
 * - Improve battery life
 * - Prevent rate limiting
 */
class SyncDebouncer(
    private val storage: SecureStorage,
    private val debounceIntervalMs: Long = DEFAULT_DEBOUNCE_INTERVAL_MS
) {
    companion object {
        private const val TAG = "VOID_SYNC"
        private const val KEY_LAST_SYNC_TIME = "sync.last_sync_time"

        /**
         * Default debounce interval: 5 minutes (300,000 ms)
         * This ensures max 288 syncs/day per user instead of potentially thousands
         */
        const val DEFAULT_DEBOUNCE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        /**
         * Emergency override interval: 10 seconds
         * Used when force=true is requested (e.g., user opens chat)
         */
        const val EMERGENCY_DEBOUNCE_INTERVAL_MS = 10 * 1000L // 10 seconds
    }

    /**
     * Check if enough time has elapsed since the last sync.
     *
     * @param force If true, uses emergency debounce interval (10s instead of 5min)
     * @return true if sync should proceed, false if it should be skipped
     */
    suspend fun shouldSync(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val lastSyncTime = getLastSyncTime() ?: 0L
        val timeSinceLastSync = now - lastSyncTime

        val effectiveInterval = if (force) EMERGENCY_DEBOUNCE_INTERVAL_MS else debounceIntervalMs

        val shouldSync = timeSinceLastSync >= effectiveInterval

        if (!shouldSync) {
            val remainingMs = effectiveInterval - timeSinceLastSync
            val remainingSec = remainingMs / 1000
            Log.d(TAG, "⏭️  [DEBOUNCE] Sync skipped - last sync ${timeSinceLastSync / 1000}s ago, " +
                    "wait ${remainingSec}s more (${if (force) "emergency" else "normal"} interval)")
        } else {
            Log.d(TAG, "✓ [DEBOUNCE] Sync allowed - ${timeSinceLastSync / 1000}s since last sync")
        }

        return shouldSync
    }

    /**
     * Record that a sync has occurred.
     * Should be called immediately before starting the actual sync operation.
     */
    suspend fun recordSync() {
        val now = System.currentTimeMillis()
        storage.put(KEY_LAST_SYNC_TIME, now.toString().toByteArray())
        Log.d(TAG, "📝 [DEBOUNCE] Recorded sync at timestamp: $now")
    }

    /**
     * Get the timestamp of the last sync.
     */
    suspend fun getLastSyncTime(): Long? {
        val bytes = storage.get(KEY_LAST_SYNC_TIME) ?: return null
        return try {
            bytes.decodeToString().toLongOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️  [DEBOUNCE] Failed to read last sync time: ${e.message}")
            null
        }
    }

    /**
     * Get time remaining until next sync is allowed (in milliseconds).
     * Returns 0 if sync is currently allowed.
     */
    suspend fun getTimeUntilNextSync(force: Boolean = false): Long {
        val lastSyncTime = getLastSyncTime() ?: return 0L
        val now = System.currentTimeMillis()
        val timeSinceLastSync = now - lastSyncTime
        val effectiveInterval = if (force) EMERGENCY_DEBOUNCE_INTERVAL_MS else debounceIntervalMs
        val remaining = effectiveInterval - timeSinceLastSync
        return maxOf(0L, remaining)
    }

    /**
     * Reset the debounce state (for testing or manual override).
     */
    suspend fun reset() {
        storage.delete(KEY_LAST_SYNC_TIME)
        Log.d(TAG, "🔄 [DEBOUNCE] Reset - next sync will be allowed")
    }
}
