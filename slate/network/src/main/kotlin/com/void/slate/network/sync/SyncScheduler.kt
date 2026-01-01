package com.void.slate.network.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.void.slate.network.push.PushRegistration
import java.util.concurrent.TimeUnit

/**
 * Schedules message synchronization and mailbox rotation tasks.
 *
 * ## Scheduling Strategy
 * 1. **FCM-Triggered Sync**: Immediate sync when push notification arrives
 * 2. **Periodic Sync**: Fallback sync every 6 hours (for missed pushes)
 * 3. **Mailbox Rotation**: Daily check for mailbox rotation needs
 * 4. **Noise Floor**: Periodic decoy traffic (privacy feature)
 *
 * ## WorkManager Integration
 * Uses Android WorkManager for:
 * - Guaranteed execution even if app is killed
 * - Battery-optimized scheduling
 * - Automatic retry with exponential backoff
 * - Survives device reboots
 *
 * ## Usage
 * ```kotlin
 * val scheduler = SyncScheduler(context)
 * scheduler.schedulePeriodicSync()
 * scheduler.scheduleRotationCheck()
 * ```
 */
class SyncScheduler(
    private val context: Context,
    private val pushRegistration: PushRegistration
) {

    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedule periodic message sync as fallback.
     *
     * This runs even if FCM push notifications fail (e.g., Google Play Services unavailable).
     * Sync interval: 6 hours (configurable).
     *
     * @param intervalHours Sync interval in hours (default: 6)
     */
    fun schedulePeriodicSync(intervalHours: Long = PERIODIC_SYNC_INTERVAL_HOURS) {
        Log.d(TAG, "📅 Scheduling periodic sync every $intervalHours hours")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Use class name string to avoid module dependency issues
        @Suppress("UNCHECKED_CAST")
        val workerClass = Class.forName("com.void.block.messaging.sync.MessageSyncWorker") as Class<out androidx.work.ListenableWorker>

        val syncRequest = PeriodicWorkRequest.Builder(
            workerClass,
            intervalHours, TimeUnit.HOURS,
            15, TimeUnit.MINUTES // Flex period: can run 15 min early
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_PERIODIC_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
            syncRequest
        )

        Log.d(TAG, "✓ Periodic sync scheduled")
    }

    /**
     * Trigger immediate one-time sync.
     *
     * Called when:
     * - FCM push notification arrives
     * - User manually refreshes
     * - App comes to foreground after long absence
     *
     * Uses expedited work for faster delivery (Android 12+).
     */
    fun triggerImmediateSync() {
        Log.d(TAG, "⚡ Triggering immediate sync")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Use class name string to avoid module dependency issues
        @Suppress("UNCHECKED_CAST")
        val workerClass = Class.forName("com.void.block.messaging.sync.MessageSyncWorker") as Class<out androidx.work.ListenableWorker>

        val syncRequest = OneTimeWorkRequest.Builder(workerClass)
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_IMMEDIATE_SYNC)
            .build()

        workManager.enqueueUniqueWork(
            WORK_IMMEDIATE_SYNC,
            ExistingWorkPolicy.KEEP, // Don't duplicate if already running
            syncRequest
        )

        Log.d(TAG, "✓ Immediate sync queued")
    }

    /**
     * Schedule mailbox rotation checks.
     *
     * Runs daily to check if mailbox needs rotation (every 25 hours).
     * If rotation needed, updates push registration with new mailbox.
     *
     * @param intervalHours Check interval in hours (default: 24)
     */
    fun scheduleRotationCheck(intervalHours: Long = ROTATION_CHECK_INTERVAL_HOURS) {
        Log.d(TAG, "📅 Scheduling mailbox rotation check every $intervalHours hours")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Use class name string to avoid module dependency issues
        // MailboxRotationWorker is in app module where it has access to all dependencies
        @Suppress("UNCHECKED_CAST")
        val workerClass = try {
            Class.forName("com.void.app.worker.MailboxRotationWorker") as Class<out androidx.work.ListenableWorker>
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "⚠️  MailboxRotationWorker not found - rotation disabled")
            return
        }

        val rotationRequest = PeriodicWorkRequest.Builder(
            workerClass,
            intervalHours, TimeUnit.HOURS,
            15, TimeUnit.MINUTES // Flex period: can run 15 min early
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_ROTATION_CHECK)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_ROTATION_CHECK,
            ExistingPeriodicWorkPolicy.KEEP,
            rotationRequest
        )

        Log.d(TAG, "✓ Rotation check scheduled")
    }

    /**
     * Cancel all scheduled sync tasks.
     *
     * Called when:
     * - User signs out
     * - Push notifications disabled
     * - App uninstalled
     */
    fun cancelAllSync() {
        Log.d(TAG, "🛑 Cancelling all scheduled sync tasks")

        workManager.cancelUniqueWork(WORK_PERIODIC_SYNC)
        workManager.cancelUniqueWork(WORK_ROTATION_CHECK)
        workManager.cancelAllWorkByTag(TAG_PERIODIC_SYNC)
        workManager.cancelAllWorkByTag(TAG_IMMEDIATE_SYNC)
        workManager.cancelAllWorkByTag(TAG_ROTATION_CHECK)

        Log.d(TAG, "✓ All sync tasks cancelled")
    }

    /**
     * Get sync work status for diagnostics.
     */
    fun getSyncStatus(): SyncStatus {
        val periodicWork = workManager.getWorkInfosForUniqueWork(WORK_PERIODIC_SYNC).get()
        val rotationWork = workManager.getWorkInfosForUniqueWork(WORK_ROTATION_CHECK).get()

        return SyncStatus(
            isPeriodicSyncScheduled = periodicWork.any { it.state != WorkInfo.State.CANCELLED },
            isRotationCheckScheduled = rotationWork.any { it.state != WorkInfo.State.CANCELLED },
            lastSyncTime = 0L // TODO: Implement sync time tracking
        )
    }

    companion object {
        private const val TAG = "SyncScheduler"

        // Work names
        private const val WORK_PERIODIC_SYNC = "void_periodic_message_sync"
        private const val WORK_IMMEDIATE_SYNC = "void_immediate_message_sync"
        private const val WORK_ROTATION_CHECK = "void_mailbox_rotation_check"

        // Work tags
        private const val TAG_PERIODIC_SYNC = "periodic_sync"
        private const val TAG_IMMEDIATE_SYNC = "immediate_sync"
        private const val TAG_ROTATION_CHECK = "rotation_check"

        // Intervals
        private const val PERIODIC_SYNC_INTERVAL_HOURS = 6L
        private const val ROTATION_CHECK_INTERVAL_HOURS = 24L
    }
}

// MessageSyncWorker implementation is in blocks/messaging/sync/MessageSyncWorker.kt
// MailboxRotationWorker implementation is in app/src/main/kotlin/com/void/app/worker/MailboxRotationWorker.kt

/**
 * Sync status information.
 */
data class SyncStatus(
    val isPeriodicSyncScheduled: Boolean,
    val isRotationCheckScheduled: Boolean,
    val lastSyncTime: Long
)
