package com.void.block.messaging.adaptive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Settings manager for INSTANT-VOID adaptive protocol.
 *
 * Stores configuration in SharedPreferences for easy access and modification.
 * Provides safe concurrent access and change notifications.
 *
 * Features:
 * - Persists configuration across app restarts
 * - Thread-safe access
 * - Default configuration (disabled by default to not break existing behavior)
 * - Quick presets (MAX_SPEED, BALANCED, MAX_PRIVACY)
 */
class InstantVoidSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    companion object {
        private const val TAG = "InstantVoidSettings"
        private const val PREFS_NAME = "instant_void_settings"
        private const val KEY_CONFIG = "config"
    }

    /**
     * Get current configuration.
     *
     * @return The current InstantVoidConfig
     */
    suspend fun getConfig(): InstantVoidConfig {
        return mutex.withLock {
            try {
                val configJson = prefs.getString(KEY_CONFIG, null)
                if (configJson != null) {
                    json.decodeFromString<InstantVoidConfig>(configJson)
                } else {
                    // Return default config if not set
                    InstantVoidConfig.DEFAULT
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config: ${e.message}")
                InstantVoidConfig.DEFAULT
            }
        }
    }

    /**
     * Update configuration.
     *
     * @param config The new configuration
     */
    suspend fun setConfig(config: InstantVoidConfig) {
        mutex.withLock {
            try {
                val configJson = json.encodeToString(config)
                prefs.edit()
                    .putString(KEY_CONFIG, configJson)
                    .apply()

                Log.d(TAG, "Configuration updated: enabled=${config.enabled}, webSocket=${config.enableWebSocket}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save config: ${e.message}")
            }
        }
    }

    /**
     * Enable INSTANT-VOID adaptive mode.
     */
    suspend fun enable() {
        val config = getConfig()
        setConfig(config.copy(enabled = true))
    }

    /**
     * Disable INSTANT-VOID adaptive mode (fall back to Poisson Ghost only).
     */
    suspend fun disable() {
        val config = getConfig()
        setConfig(config.copy(enabled = false))
    }

    /**
     * Check if INSTANT-VOID is enabled.
     */
    suspend fun isEnabled(): Boolean {
        return getConfig().enabled
    }

    /**
     * Enable WebSocket for ACTIVE mode.
     */
    suspend fun enableWebSocket() {
        val config = getConfig()
        setConfig(config.copy(enableWebSocket = true))
    }

    /**
     * Disable WebSocket (use polling only).
     */
    suspend fun disableWebSocket() {
        val config = getConfig()
        setConfig(config.copy(enableWebSocket = false))
    }

    /**
     * Enable cover traffic (decoy messages).
     */
    suspend fun enableCoverTraffic() {
        val config = getConfig()
        setConfig(config.copy(coverTrafficEnabled = true))
    }

    /**
     * Disable cover traffic.
     */
    suspend fun disableCoverTraffic() {
        val config = getConfig()
        setConfig(config.copy(coverTrafficEnabled = false))
    }

    /**
     * Apply a preset configuration.
     *
     * @param preset The preset to apply
     */
    suspend fun applyPreset(preset: InstantVoidPreset) {
        val config = when (preset) {
            InstantVoidPreset.MAX_SPEED -> InstantVoidConfig.MAX_SPEED
            InstantVoidPreset.BALANCED -> InstantVoidConfig.BALANCED
            InstantVoidPreset.MAX_PRIVACY -> InstantVoidConfig.MAX_PRIVACY
            InstantVoidPreset.DEFAULT -> InstantVoidConfig.DEFAULT
        }
        setConfig(config)
        Log.d(TAG, "Applied preset: $preset")
    }

    /**
     * Reset to default configuration.
     */
    suspend fun reset() {
        setConfig(InstantVoidConfig.DEFAULT)
        Log.d(TAG, "Reset to default configuration")
    }

    /**
     * Enable debug mode (extra logging).
     */
    suspend fun enableDebug() {
        val config = getConfig()
        setConfig(config.copy(debugMode = true))
    }

    /**
     * Disable debug mode.
     */
    suspend fun disableDebug() {
        val config = getConfig()
        setConfig(config.copy(debugMode = false))
    }
}

/**
 * Preset configurations for quick setup.
 */
enum class InstantVoidPreset {
    /**
     * Default configuration (adaptive mode disabled).
     * Falls back to Poisson Ghost protocol only.
     */
    DEFAULT,

    /**
     * Maximum speed configuration.
     * - Adaptive mode enabled
     * - WebSocket enabled for ACTIVE mode
     * - Minimum polling intervals
     * - Cover traffic enabled
     *
     * Best for: Users who prioritize instant delivery
     * Battery impact: Medium-High
     * Privacy: High
     */
    MAX_SPEED,

    /**
     * Balanced configuration (recommended).
     * - Adaptive mode enabled
     * - WebSocket disabled (polling only)
     * - Moderate polling intervals
     * - Cover traffic enabled
     *
     * Best for: Most users
     * Battery impact: Low-Medium
     * Privacy: Very High
     */
    BALANCED,

    /**
     * Maximum privacy configuration.
     * - Adaptive mode disabled
     * - Falls back to Poisson Ghost exclusively
     * - No WebSocket
     * - No cover traffic (relies on Poisson timing)
     *
     * Best for: High-risk users, journalists, activists
     * Battery impact: Minimal
     * Privacy: Maximum
     */
    MAX_PRIVACY
}
