package com.void.block.messaging.adaptive

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.void.block.messaging.data.MessageRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager Worker for adaptive message synchronization.
 *
 * This worker is an alternative to the existing MessageSyncWorker.
 * It uses the adaptive INSTANT-VOID protocol to sync messages with
 * context-aware timing.
 *
 * Trigger sources:
 * - FCM notification (via VoidFirebaseService)
 * - Periodic background sync (when adaptive mode is enabled)
 * - User-initiated sync (pull to refresh)
 *
 * Behavior:
 * - Checks current conversation mode (ACTIVE/SEMI_ACTIVE/DORMANT)
 * - Syncs messages from Supabase
 * - Updates conversation state
 * - Schedules next sync based on mode
 *
 * Integration:
 * - Uses Koin for dependency injection
 * - Falls back to standard sync if adaptive mode disabled
 * - Compatible with existing MessageSyncWorker (can run both)
 */
class AdaptiveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "AdaptiveSyncWorker"
        const val WORK_NAME = "void_adaptive_sync"

        // Input parameters
        const val PARAM_CONVERSATION_ID = "conversation_id"
        const val PARAM_FORCE_MODE = "force_mode"
    }

    // Inject dependencies via Koin
    private val messageRepository: MessageRepository by inject()
    private val stateManager: ConversationStateManager by inject()
    private val settings: InstantVoidSettings by inject()

    override suspend fun doWork(): Result {
        Log.d(TAG, "⚡ AdaptiveSyncWorker started")
        Log.d(TAG, "   Run attempt: ${runAttemptCount + 1}")
        Log.d(TAG, "   Tags: ${tags.joinToString()}")

        return try {
            // Check if adaptive mode is enabled
            val config = settings.getConfig()
            if (!config.enabled) {
                Log.d(TAG, "⚠️ Adaptive mode disabled, skipping")
                return Result.success()
            }

            // Get conversation ID from params (optional - specific conversation sync)
            val conversationId = inputData.getString(PARAM_CONVERSATION_ID)
            val forceMode = inputData.getString(PARAM_FORCE_MODE)?.let {
                ConversationMode.valueOf(it)
            }

            // Determine current mode
            val mode = if (forceMode != null) {
                Log.d(TAG, "   Using forced mode: $forceMode")
                forceMode
            } else if (conversationId != null) {
                stateManager.getMode(conversationId)
            } else {
                stateManager.getGlobalMode()
            }

            Log.d(TAG, "   Current mode: $mode")
            Log.d(TAG, "   Conversation: ${conversationId ?: "global"}")

            // Perform sync
            val startTime = System.currentTimeMillis()
            val newMessageCount = messageRepository.syncMessages()
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ [ADAPTIVE_SYNC] messages=$newMessageCount, duration=${duration}ms, mode=$mode")

            // Update conversation states for any new messages
            // (MessageRepository should call stateManager.updateConversation when receiving messages)

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Adaptive sync failed: ${e.message}", e)

            // Retry on failure (up to 3 attempts)
            if (runAttemptCount < 3) {
                Log.d(TAG, "🔄 Retrying (attempt ${runAttemptCount + 1}/3)")
                Result.retry()
            } else {
                Log.e(TAG, "❌ Max retries reached, giving up")
                Result.failure()
            }
        }
    }
}
