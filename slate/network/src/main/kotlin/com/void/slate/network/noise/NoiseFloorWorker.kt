package com.void.slate.network.noise

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.void.slate.network.supabase.MessageSender
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Generates background noise traffic to hide real messaging patterns.
 *
 * ## Privacy Architecture
 * Traffic analysis can reveal:
 * - When users are active
 * - Message frequency patterns
 * - Correlation between senders and recipients
 *
 * Noise floor mitigates this by:
 * - Sending decoy messages at random intervals
 * - Creating constant background traffic
 * - Making it harder to distinguish real from fake messages
 * - Obscuring true communication patterns
 *
 * ## Noise Strategy
 * 1. Send 1-3 decoy messages per run
 * 2. Random payload sizes (512 bytes - 4 KB)
 * 3. Random mailbox destinations
 * 4. Exponential timing jitter
 * 5. Battery-aware throttling
 *
 * ## Scheduling
 * - Runs every 4-8 hours (randomized)
 * - Requires network connectivity
 * - Skips when battery is critical
 * - Pauses during Hostile Mode (persistent connection already provides cover)
 *
 * ## Usage
 * ```kotlin
 * // Schedule noise floor
 * NoiseFloorScheduler.schedule(context)
 *
 * // Cancel noise floor
 * NoiseFloorScheduler.cancel(context)
 * ```
 */
class NoiseFloorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    // Inject MessageSender via Koin
    private val messageSender: MessageSender by inject()

    override suspend fun doWork(): Result {
        Log.d(TAG, "🎭 Noise floor worker started")

        return try {
            // Generate 1-3 decoy messages
            val decoyCount = Random.nextInt(MIN_DECOYS_PER_RUN, MAX_DECOYS_PER_RUN + 1)

            Log.d(TAG, "   Sending $decoyCount decoy messages")

            repeat(decoyCount) { index ->
                // Random payload size (512 bytes - 4 KB)
                val payloadSize = Random.nextInt(MIN_DECOY_SIZE, MAX_DECOY_SIZE)

                // Send decoy message
                val result = messageSender.sendDecoyMessage(payloadSize)

                result.fold(
                    onSuccess = {
                        Log.d(TAG, "   ✓ Decoy ${index + 1}/$decoyCount sent ($payloadSize bytes)")
                    },
                    onFailure = { error ->
                        Log.w(TAG, "   ⚠️  Decoy ${index + 1}/$decoyCount failed: ${error.message}")
                    }
                )

                // Add jitter between decoys (0-5 seconds)
                if (index < decoyCount - 1) {
                    val jitter = Random.nextLong(0, INTER_DECOY_JITTER_MS)
                    delay(jitter)
                }
            }

            Log.d(TAG, "✅ Noise floor worker completed successfully")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Noise floor worker failed: ${e.message}", e)

            // Retry with backoff if this was a transient error
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Log.d(TAG, "   🔄 Retrying (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS)")
                Result.retry()
            } else {
                Log.e(TAG, "   ❌ Max retries reached, giving up")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "NoiseFloorWorker"

        // Decoy parameters
        private const val MIN_DECOYS_PER_RUN = 1
        private const val MAX_DECOYS_PER_RUN = 3
        private const val MIN_DECOY_SIZE = 512 // bytes
        private const val MAX_DECOY_SIZE = 4096 // bytes (4 KB)

        // Timing parameters
        private const val INTER_DECOY_JITTER_MS = 5000L // 0-5 seconds between decoys

        // Retry parameters
        private const val MAX_RETRY_ATTEMPTS = 2
    }
}

/**
 * Scheduler for NoiseFloorWorker.
 *
 * Manages the periodic scheduling of noise floor traffic.
 */
object NoiseFloorScheduler {

    private const val TAG = "NoiseFloorScheduler"
    private const val WORK_NAME = "void_noise_floor"
    private const val WORK_TAG = "noise_floor"

    /**
     * Schedule periodic noise floor traffic.
     *
     * @param context Application context
     * @param intervalHours Base interval in hours (default: 6)
     * @param flexHours Flex period for randomization (default: 2)
     */
    fun schedule(
        context: Context,
        intervalHours: Long = DEFAULT_INTERVAL_HOURS,
        flexHours: Long = DEFAULT_FLEX_HOURS
    ) {
        Log.d(TAG, "📅 Scheduling noise floor every $intervalHours hours (±$flexHours hours)")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true) // Don't drain battery
            .build()

        val noiseRequest = PeriodicWorkRequestBuilder<NoiseFloorWorker>(
            intervalHours, TimeUnit.HOURS,
            flexHours, TimeUnit.HOURS // Randomize timing
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            noiseRequest
        )

        Log.d(TAG, "✓ Noise floor scheduled")
    }

    /**
     * Cancel noise floor traffic.
     *
     * Called when:
     * - User disables privacy features
     * - Hostile Mode is enabled (persistent connection provides cover)
     * - App is uninstalled
     */
    fun cancel(context: Context) {
        Log.d(TAG, "🛑 Cancelling noise floor")

        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)

        Log.d(TAG, "✓ Noise floor cancelled")
    }

    /**
     * Check if noise floor is currently scheduled.
     */
    fun isScheduled(context: Context): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()

        return workInfos.any { it.state != WorkInfo.State.CANCELLED }
    }

    /**
     * Get noise floor statistics for diagnostics.
     */
    fun getStatus(context: Context): NoiseFloorStatus {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()

        val isScheduled = workInfos.any { it.state != WorkInfo.State.CANCELLED }
        val state = workInfos.firstOrNull()?.state

        return NoiseFloorStatus(
            isScheduled = isScheduled,
            workState = state?.toString() ?: "NOT_SCHEDULED",
            runAttemptCount = workInfos.firstOrNull()?.runAttemptCount ?: 0
        )
    }

    // Default scheduling parameters
    private const val DEFAULT_INTERVAL_HOURS = 6L
    private const val DEFAULT_FLEX_HOURS = 2L
}

/**
 * Noise floor status information.
 */
data class NoiseFloorStatus(
    val isScheduled: Boolean,
    val workState: String,
    val runAttemptCount: Int
)
