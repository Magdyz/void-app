package com.void.block.messaging.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager Worker that performs message synchronization.
 *
 * This worker is triggered by:
 * - FCM payload-less push notification (the "tickle")
 * - Periodic background sync (optional, for redundancy)
 *
 * Flow:
 * 1. FCM sends empty notification to Android OS
 * 2. VoidFirebaseService catches it and enqueues this worker
 * 3. Worker spins up MessageSyncEngine
 * 4. Engine connects to server, fetches messages, decrypts, stores, notifies
 * 5. Worker completes and Android can kill the process again
 *
 * Architecture:
 * - Uses Koin for dependency injection via KoinComponent
 * - WorkManager guarantees execution even if app is killed
 * - Expedited work for faster delivery (Android 12+)
 */
class MessageSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "MessageSyncWorker"
        const val WORK_NAME = "void_message_sync"
    }

    // Inject MessageSyncEngine via Koin
    private val syncEngine: MessageSyncEngine by inject()

    override suspend fun doWork(): Result {
        Log.d(TAG, "⚡ MessageSyncWorker started")
        Log.d(TAG, "   Run attempt: ${runAttemptCount + 1}")
        Log.d(TAG, "   Tags: ${tags.joinToString()}")

        return try {
            // Perform one-time sync
            Log.d(TAG, "   Calling syncEngine.performOneTimeSync()...")
            val result = syncEngine.performOneTimeSync()

            result.fold(
                onSuccess = {
                    Log.d(TAG, "✅ Sync completed successfully")
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "❌ Sync failed: ${error.message}", error)
                    Log.e(TAG, "   Error type: ${error.javaClass.simpleName}")

                    // Retry on network errors (WorkManager will automatically retry with backoff)
                    if (runAttemptCount < 3) {
                        Log.d(TAG, "🔄 Retrying (attempt ${runAttemptCount + 1}/3)")
                        Result.retry()
                    } else {
                        Log.e(TAG, "❌ Max retries reached, giving up")
                        Result.failure()
                    }
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker exception: ${e.message}", e)
            Log.e(TAG, "   Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()

            if (runAttemptCount < 3) {
                Log.d(TAG, "🔄 Will retry due to exception")
                Result.retry()
            } else {
                Log.e(TAG, "❌ Max retries reached after exception, giving up")
                Result.failure()
            }
        }
    }
}
