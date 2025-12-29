package com.void.block.rhythm.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Captures rhythm input from user tap events.
 * Thread-safe for use with Compose gesture handlers.
 */
class RhythmCapture {

    private val taps = mutableListOf<RhythmTap>()
    private var startTime: Long = 0
    private var isCapturing = false

    private val _tapCount = MutableStateFlow(0)
    val tapCount: StateFlow<Int> = _tapCount.asStateFlow()

    /**
     * Start a new capture session.
     */
    @Synchronized
    fun start() {
        taps.clear()
        startTime = System.currentTimeMillis()
        isCapturing = true
        _tapCount.value = 0
    }

    /**
     * Record a tap event.
     *
     * @param pressure Tap pressure 0.0-1.0 (use 1.0 if unavailable)
     * @param x Normalized X position 0.0-1.0
     * @param y Normalized Y position 0.0-1.0
     * @param duration How long tap was held in ms
     */
    @Synchronized
    fun recordTap(
        pressure: Float = 1f,
        x: Float = 0.5f,
        y: Float = 0.5f,
        duration: Long = 100L
    ): Boolean {
        if (!isCapturing) return false
        if (taps.size >= RhythmPattern.MAX_TAPS) return false

        val timestamp = System.currentTimeMillis() - startTime
        if (timestamp > RhythmPattern.MAX_DURATION_MS) return false

        taps.add(RhythmTap(
            timestamp = timestamp,
            pressure = pressure.coerceIn(0f, 1f),
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            duration = duration
        ))

        _tapCount.value = taps.size
        return true
    }

    /**
     * Finish capturing and return the pattern.
     * Returns null if minimum requirements not met.
     */
    @Synchronized
    fun finish(): RhythmPattern? {
        isCapturing = false

        if (taps.size < RhythmPattern.MIN_TAPS) {
            return null
        }

        return RhythmPattern(
            taps = taps.toList(),
            totalDuration = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Cancel the current capture session.
     */
    @Synchronized
    fun cancel() {
        isCapturing = false
        taps.clear()
        _tapCount.value = 0
    }

    /**
     * Check if currently capturing.
     */
    fun isActive(): Boolean = isCapturing
}
