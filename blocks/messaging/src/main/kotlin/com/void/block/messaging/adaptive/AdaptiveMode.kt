package com.void.block.messaging.adaptive

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Adaptive conversation modes for INSTANT-VOID protocol.
 *
 * The system automatically switches between these modes based on conversation activity:
 * - ACTIVE: Recent messaging activity (< 5 minutes) - near real-time delivery
 * - SEMI_ACTIVE: Some recent activity (5-30 minutes) - moderate polling
 * - DORMANT: No recent activity (> 30 minutes) - Poisson timing for maximum privacy
 */
@Serializable
enum class ConversationMode {
    /**
     * Active conversation mode - last message within 5 minutes.
     *
     * Characteristics:
     * - Polling interval: 3-5 seconds (near real-time)
     * - WebSocket: Optional (can be enabled for instant delivery)
     * - Decoy traffic: High (1-3 per sync, randomized)
     * - Battery impact: Medium
     * - Privacy: High (constant decoy noise)
     *
     * Delivery time: 1-5 seconds
     */
    ACTIVE {
        override fun getPollingInterval(): Duration = 3.seconds
        override fun getDecoyCount(): Int = kotlin.random.Random.nextInt(1, 4) // 1-3 decoys
        override fun shouldUseWebSocket(): Boolean = false // Start with polling, can enable later
    },

    /**
     * Semi-active conversation mode - last message 5-30 minutes ago.
     *
     * Characteristics:
     * - Polling interval: 30-90 seconds
     * - WebSocket: Disabled
     * - Decoy traffic: Medium (1-3 per sync, randomized)
     * - Battery impact: Low
     * - Privacy: Very High
     *
     * Delivery time: 30-90 seconds
     */
    SEMI_ACTIVE {
        override fun getPollingInterval(): Duration = 60.seconds
        override fun getDecoyCount(): Int = kotlin.random.Random.nextInt(1, 4) // 1-3 decoys
        override fun shouldUseWebSocket(): Boolean = false
    },

    /**
     * Dormant conversation mode - no activity for 30+ minutes.
     *
     * Characteristics:
     * - Polling interval: Poisson distribution (5-20 min average)
     * - WebSocket: Disabled
     * - Decoy traffic: Low (0-2 per sync, randomized)
     * - Battery impact: Minimal
     * - Privacy: Maximum (Poisson Ghost protocol)
     *
     * Delivery time: 5-20 minutes (maximum privacy)
     */
    DORMANT {
        override fun getPollingInterval(): Duration = 10.minutes // Average Poisson interval
        override fun getDecoyCount(): Int = kotlin.random.Random.nextInt(0, 3) // 0-2 decoys
        override fun shouldUseWebSocket(): Boolean = false
    };

    /**
     * Get the polling interval for this mode.
     */
    abstract fun getPollingInterval(): Duration

    /**
     * Get the number of decoy messages to send with each real message.
     */
    abstract fun getDecoyCount(): Int

    /**
     * Whether WebSocket should be used in this mode.
     */
    abstract fun shouldUseWebSocket(): Boolean
}

/**
 * Thresholds for conversation mode detection.
 *
 * These define when to transition between modes based on time since last message.
 */
object ConversationThresholds {
    /** Threshold for ACTIVE mode (< 5 minutes since last message) */
    val ACTIVE_THRESHOLD: Duration = 5.minutes

    /** Threshold for SEMI_ACTIVE mode (5-30 minutes since last message) */
    val SEMI_ACTIVE_THRESHOLD: Duration = 30.minutes

    /** Messages older than this are considered DORMANT */
    val DORMANT_THRESHOLD: Duration = 30.minutes
}

/**
 * State information for a conversation used to determine adaptive mode.
 */
@Serializable
data class ConversationState(
    val conversationId: String,
    val lastMessageTimestamp: Long = 0,
    val messageCount: Int = 0,
    val lastSyncTimestamp: Long = System.currentTimeMillis(),
    val currentMode: ConversationMode = ConversationMode.DORMANT
) {
    /**
     * Calculate time since last message.
     */
    fun timeSinceLastMessage(): Duration {
        return (System.currentTimeMillis() - lastMessageTimestamp).milliseconds
    }

    /**
     * Determine the appropriate mode based on current state.
     */
    fun determineMode(): ConversationMode {
        val timeSince = timeSinceLastMessage()

        return when {
            timeSince < ConversationThresholds.ACTIVE_THRESHOLD -> ConversationMode.ACTIVE
            timeSince < ConversationThresholds.SEMI_ACTIVE_THRESHOLD -> ConversationMode.SEMI_ACTIVE
            else -> ConversationMode.DORMANT
        }
    }

    /**
     * Check if mode has changed and should trigger a transition.
     */
    fun shouldTransitionMode(): Boolean {
        val newMode = determineMode()
        return newMode != currentMode
    }
}

/**
 * Statistics for adaptive sync system (for debugging and monitoring).
 */
@Serializable
data class AdaptiveSyncStats(
    val totalSyncs: Int = 0,
    val activeSyncs: Int = 0,
    val semiActiveSyncs: Int = 0,
    val dormantSyncs: Int = 0,
    val averagePollingInterval: Duration = 0.milliseconds,
    val lastSyncTimestamp: Long = 0,
    val decoySent: Int = 0
) {
    /**
     * Record a sync operation.
     */
    fun recordSync(mode: ConversationMode, decoysCount: Int = 0): AdaptiveSyncStats {
        return when (mode) {
            ConversationMode.ACTIVE -> copy(
                totalSyncs = totalSyncs + 1,
                activeSyncs = activeSyncs + 1,
                decoySent = decoySent + decoysCount,
                lastSyncTimestamp = System.currentTimeMillis()
            )
            ConversationMode.SEMI_ACTIVE -> copy(
                totalSyncs = totalSyncs + 1,
                semiActiveSyncs = semiActiveSyncs + 1,
                decoySent = decoySent + decoysCount,
                lastSyncTimestamp = System.currentTimeMillis()
            )
            ConversationMode.DORMANT -> copy(
                totalSyncs = totalSyncs + 1,
                dormantSyncs = dormantSyncs + 1,
                decoySent = decoySent + decoysCount,
                lastSyncTimestamp = System.currentTimeMillis()
            )
        }
    }
}

/**
 * Configuration for INSTANT-VOID adaptive system.
 *
 * Allows users to customize behavior and performance/privacy trade-offs.
 */
@Serializable
data class InstantVoidConfig(
    /**
     * Enable/disable the adaptive system entirely.
     * When false, falls back to Poisson Ghost only.
     */
    val enabled: Boolean = false, // Default: OFF to not break existing functionality

    /**
     * Enable WebSocket connections for ACTIVE mode.
     * Provides lowest latency but reveals "someone is chatting" to ISP.
     */
    val enableWebSocket: Boolean = false,

    /**
     * Minimum polling interval (safety limit to prevent battery drain).
     */
    val minPollingInterval: Duration = 3.seconds,

    /**
     * Maximum polling interval (when in DORMANT mode).
     */
    val maxPollingInterval: Duration = 20.minutes,

    /**
     * Battery optimization: Reduce polling frequency when battery is low.
     */
    val batteryOptimizationEnabled: Boolean = true,

    /**
     * Low battery threshold (percentage).
     * When battery is below this, reduce polling frequency.
     */
    val lowBatteryThreshold: Int = 20,

    /**
     * Enable cover traffic (decoy messages).
     * Enabled by default for zero-leakage security.
     */
    val coverTrafficEnabled: Boolean = true,

    /**
     * Debug mode: Extra logging for troubleshooting.
     */
    val debugMode: Boolean = false
) {
    companion object {
        /**
         * Default configuration (safe, doesn't break existing functionality).
         */
        val DEFAULT = InstantVoidConfig(
            enabled = false, // Disabled by default
            enableWebSocket = false,
            coverTrafficEnabled = true // Enabled for zero-leakage
        )

        /**
         * Maximum speed configuration (lowest latency).
         */
        val MAX_SPEED = InstantVoidConfig(
            enabled = true,
            enableWebSocket = true,
            minPollingInterval = 2.seconds,
            coverTrafficEnabled = true
        )

        /**
         * Balanced configuration (good latency + privacy).
         */
        val BALANCED = InstantVoidConfig(
            enabled = true,
            enableWebSocket = false,
            minPollingInterval = 3.seconds,
            maxPollingInterval = 15.minutes,
            coverTrafficEnabled = true
        )

        /**
         * Maximum privacy configuration (Poisson only).
         */
        val MAX_PRIVACY = InstantVoidConfig(
            enabled = false, // Use Poisson Ghost exclusively
            enableWebSocket = false,
            coverTrafficEnabled = false
        )
    }
}
