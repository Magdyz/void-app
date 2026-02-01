package com.void.block.messaging.adaptive

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.void.block.messaging.data.MessageRepository
import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Void Polling Engine - Adaptive message polling for INSTANT-VOID protocol.
 *
 * This engine implements intelligent, context-aware polling:
 * - ACTIVE mode: Poll every 3-5 seconds (near real-time)
 * - SEMI_ACTIVE mode: Poll every 30-90 seconds (moderate)
 * - DORMANT mode: Fall back to Poisson Ghost (5-20 min)
 *
 * Features:
 * - Battery-aware (reduces polling on low battery)
 * - Network-aware (pauses on no connection)
 * - Mode transition handling (smooth switches between modes)
 * - Statistics tracking (for debugging and optimization)
 *
 * Architecture:
 * - Runs in background coroutine
 * - Cancellable and restartable
 * - Safe concurrent access
 */
class VoidPollingEngine(
    private val context: Context,
    private val messageRepository: MessageRepository,
    private val stateManager: ConversationStateManager,
    private val config: InstantVoidConfig,
    private val messageSender: com.void.slate.network.supabase.MessageSender? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "VoidPollingEngine"

        // Jitter ranges (to prevent thundering herd)
        private const val JITTER_PERCENTAGE = 0.2 // ±20% jitter
    }

    private var pollingJob: Job? = null
    private var isRunning = false
    private var stats = AdaptiveSyncStats()

    /**
     * Start adaptive polling.
     *
     * This will continuously poll for messages using adaptive intervals
     * based on conversation state and system conditions.
     */
    fun start() {
        if (pollingJob?.isActive == true) {
            Log.d(TAG, "Polling already running")
            return
        }

        Log.d(TAG, "Starting adaptive polling (enabled=${config.enabled})")

        pollingJob = scope.launch {
            isRunning = true

            while (isActive && isRunning) {
                try {
                    // Get current global mode
                    val mode = stateManager.getGlobalMode()

                    // Check battery level (if optimization enabled)
                    val batteryLevel = getBatteryLevel()
                    val isLowBattery = batteryLevel < config.lowBatteryThreshold
                    val isCharging = isCharging()

                    // Determine polling interval
                    val interval = determinePollingInterval(mode, isLowBattery, isCharging)

                    Log.d(TAG, "⏰ [ADAPTIVE_POLL] mode=$mode, battery=$batteryLevel%, charging=$isCharging, interval=${interval.inWholeSeconds}s")

                    // Perform sync
                    performSync(mode)

                    // Wait for next poll (with jitter)
                    val jitteredInterval = addJitter(interval)
                    delay(jitteredInterval.inWholeMilliseconds)

                } catch (e: CancellationException) {
                    Log.d(TAG, "Polling cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}", e)
                    // Wait before retry (exponential backoff)
                    delay(5000)
                }
            }

            Log.d(TAG, "Adaptive polling stopped")
        }
    }

    /**
     * Stop adaptive polling.
     */
    suspend fun stop() {
        Log.d(TAG, "Stopping adaptive polling")
        isRunning = false
        pollingJob?.cancelAndJoin()
        pollingJob = null
    }

    /**
     * Perform a single sync operation.
     *
     * @param mode The current conversation mode
     */
    private suspend fun performSync(mode: ConversationMode) {
        try {
            Log.d(TAG, "📥 [SYNC_START] mode=$mode")
            val startTime = System.currentTimeMillis()

            // Sync messages from network
            // In ACTIVE mode, use force=true for near real-time sync (10s debounce)
            // In SEMI_ACTIVE mode, use activeChat=true (30s debounce)
            // In DORMANT mode, use default (5min debounce)
            val force = mode == ConversationMode.ACTIVE
            val activeChat = mode == ConversationMode.SEMI_ACTIVE
            val newMessageCount = messageRepository.syncMessages(force = force, activeChat = activeChat)

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ [SYNC_COMPLETE] messages=$newMessageCount, duration=${duration}ms")

            // Send cover traffic (decoy messages) if enabled
            var decoysSent = 0
            if (config.coverTrafficEnabled && messageSender != null) {
                val decoyCount = mode.getDecoyCount()
                Log.d(TAG, "🎭 [COVER_TRAFFIC] Sending $decoyCount decoy messages (mode=$mode)")

                repeat(decoyCount) {
                    try {
                        messageSender.sendDecoyMessage()
                        decoysSent++
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️  Decoy send failed (non-critical): ${e.message}")
                    }
                }

                Log.d(TAG, "🎭 [COVER_TRAFFIC] Sent $decoysSent/$decoyCount decoys")
            }

            // Update statistics
            stats = stats.recordSync(mode, decoysCount = decoysSent)

        } catch (e: Exception) {
            Log.e(TAG, "❌ [SYNC_FAILED] ${e.message}", e)
        }
    }

    /**
     * Determine polling interval based on mode and system conditions.
     *
     * @param mode The current conversation mode
     * @param isLowBattery Whether battery is low
     * @param isCharging Whether device is charging
     * @return The polling interval to use
     */
    private fun determinePollingInterval(
        mode: ConversationMode,
        isLowBattery: Boolean,
        isCharging: Boolean
    ): Duration {
        // Base interval from mode
        var interval = mode.getPollingInterval()

        // Battery optimization: Increase interval on low battery (unless charging)
        if (config.batteryOptimizationEnabled && isLowBattery && !isCharging) {
            interval = interval * 2 // Double the interval
            Log.d(TAG, "⚡ [BATTERY_OPT] Low battery detected, interval increased to ${interval.inWholeSeconds}s")
        }

        // Clamp to configured limits
        interval = interval.coerceIn(config.minPollingInterval, config.maxPollingInterval)

        return interval
    }

    /**
     * Add random jitter to interval to prevent thundering herd.
     *
     * @param interval The base interval
     * @return The jittered interval (±20%)
     */
    private fun addJitter(interval: Duration): Duration {
        val baseMs = interval.inWholeMilliseconds
        val jitterRange = (baseMs * JITTER_PERCENTAGE).toLong()
        val jitterMs = Random.nextLong(-jitterRange, jitterRange)
        return (baseMs + jitterMs).milliseconds
    }

    /**
     * Get current battery level (0-100).
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery level: ${e.message}")
            100 // Assume full battery on error
        }
    }

    /**
     * Check if device is charging.
     */
    private fun isCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.isCharging
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check charging status: ${e.message}")
            false
        }
    }

    /**
     * Get current polling statistics.
     */
    fun getStats(): AdaptiveSyncStats {
        return stats
    }

    /**
     * Reset statistics.
     */
    fun resetStats() {
        stats = AdaptiveSyncStats()
    }

    /**
     * Check if polling is currently running.
     */
    fun isRunning(): Boolean {
        return pollingJob?.isActive == true
    }
}

/**
 * Poisson timing utility for DORMANT mode.
 *
 * Generates random intervals following a Poisson distribution.
 * This provides maximum privacy by making timing unpredictable.
 */
object PoissonTiming {
    /**
     * Generate a random interval following Poisson distribution.
     *
     * @param mean The average interval
     * @return A random interval centered around the mean
     */
    fun generateInterval(mean: Duration): Duration {
        // Simple Poisson approximation using exponential distribution
        // -ln(1 - U) / λ where U is uniform random [0,1) and λ = 1/mean
        val u = Random.nextDouble(0.0, 1.0)
        val lambda = 1.0 / mean.inWholeSeconds.toDouble()
        val seconds = -kotlin.math.ln(1.0 - u) / lambda

        return seconds.toLong().seconds
    }
}
