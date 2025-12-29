package com.void.block.rhythm.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Represents a single tap in the rhythm.
 */
@Serializable
data class RhythmTap(
    val timestamp: Long,           // Milliseconds since rhythm start
    val pressure: Float,           // 0.0 to 1.0 (if available, else 1.0)
    val x: Float,                  // Normalized 0.0 to 1.0
    val y: Float,                  // Normalized 0.0 to 1.0
    val duration: Long             // How long the tap was held (ms)
)

/**
 * Complete rhythm pattern.
 */
@Serializable
data class RhythmPattern(
    val taps: List<RhythmTap>,
    val totalDuration: Long,
    val capturedAt: Long = System.currentTimeMillis()
) {
    /**
     * Extract timing intervals between taps (most important for matching).
     */
    val intervals: List<Long>
        get() = taps.zipWithNext { a, b -> b.timestamp - a.timestamp }

    /**
     * Check if pattern meets minimum requirements.
     */
    val isValid: Boolean
        get() = taps.size in MIN_TAPS..MAX_TAPS && totalDuration <= MAX_DURATION_MS

    companion object {
        const val MIN_TAPS = 4
        const val MAX_TAPS = 20
        const val MAX_DURATION_MS = 10_000L // 10 seconds max
    }
}

/**
 * Serialization for storing rhythm patterns.
 */
object RhythmSerializer {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    fun serialize(pattern: RhythmPattern): ByteArray {
        return json.encodeToString(pattern).toByteArray()
    }

    fun deserialize(bytes: ByteArray): RhythmPattern {
        return json.decodeFromString(bytes.decodeToString())
    }
}
