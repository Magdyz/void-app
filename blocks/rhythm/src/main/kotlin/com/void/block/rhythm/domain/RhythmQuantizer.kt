package com.void.block.rhythm.domain

import kotlin.math.roundToInt

/**
 * Quantizes rhythm patterns to canonical form for storage.
 * This reduces sensitivity while maintaining pattern identity.
 */
object RhythmQuantizer {

    private const val TIME_QUANTUM_MS = 50L    // Round to nearest 50ms
    private const val PRESSURE_LEVELS = 10      // 10 discrete pressure levels
    private const val POSITION_GRID = 5         // 5x5 position grid

    /**
     * Quantize a rhythm pattern for storage.
     */
    fun quantize(pattern: RhythmPattern): RhythmPattern {
        val quantizedTaps = pattern.taps.map { tap ->
            tap.copy(
                // Quantize timestamp to nearest TIME_QUANTUM_MS
                timestamp = (tap.timestamp / TIME_QUANTUM_MS) * TIME_QUANTUM_MS,
                // Quantize pressure to discrete levels
                pressure = (tap.pressure * PRESSURE_LEVELS).roundToInt().toFloat() / PRESSURE_LEVELS,
                // Quantize position to grid
                x = (tap.x * POSITION_GRID).roundToInt().toFloat() / POSITION_GRID,
                y = (tap.y * POSITION_GRID).roundToInt().toFloat() / POSITION_GRID,
                // Quantize duration
                duration = (tap.duration / TIME_QUANTUM_MS) * TIME_QUANTUM_MS
            )
        }

        return pattern.copy(taps = quantizedTaps)
    }
}
