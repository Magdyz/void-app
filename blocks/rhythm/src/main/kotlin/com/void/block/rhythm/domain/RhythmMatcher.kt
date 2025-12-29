package com.void.block.rhythm.domain

import kotlin.math.abs

/**
 * Fuzzy matcher for rhythm patterns.
 *
 * SECURITY NOTE: This is safe because matching doesn't derive keys.
 * The rhythm only GATES access to hardware-backed Keystore keys.
 * Tolerance here improves UX without compromising security.
 */
class RhythmMatcher(
    private val timingTolerance: Float = 0.25f,      // 25% timing tolerance
    private val positionWeight: Float = 0.15f,        // 15% weight for position
    private val confidenceThreshold: Float = 0.75f    // 75% required to match
) {

    /**
     * Match two patterns and return detailed result.
     */
    fun match(stored: RhythmPattern, attempt: RhythmPattern): MatchResult {
        // Must have same number of taps
        if (stored.taps.size != attempt.taps.size) {
            return MatchResult(
                confidence = 0f,
                isMatch = false,
                details = MatchDetails.TapCountMismatch(
                    expected = stored.taps.size,
                    actual = attempt.taps.size
                )
            )
        }

        // Compare intervals (most important - 85% weight)
        val intervalScore = compareIntervals(stored.intervals, attempt.intervals)

        // Compare positions (secondary - 15% weight)
        val positionScore = comparePositions(stored.taps, attempt.taps)

        // Weighted confidence
        val confidence = intervalScore * (1f - positionWeight) + positionScore * positionWeight

        return MatchResult(
            confidence = confidence,
            isMatch = confidence >= confidenceThreshold,
            details = MatchDetails.Scored(
                intervalScore = intervalScore,
                positionScore = positionScore
            )
        )
    }

    private fun compareIntervals(stored: List<Long>, attempt: List<Long>): Float {
        if (stored.isEmpty()) return 1f

        var totalScore = 0f

        stored.zip(attempt).forEach { (expected, actual) ->
            val tolerance = expected * timingTolerance
            val diff = abs(expected - actual).toFloat()

            val score = when {
                diff <= tolerance * 0.5f -> 1.0f      // Perfect zone
                diff <= tolerance -> 0.85f             // Good zone
                diff <= tolerance * 1.5f -> 0.5f       // Acceptable zone
                diff <= tolerance * 2f -> 0.25f        // Poor zone
                else -> 0f                              // Fail zone
            }

            totalScore += score
        }

        return totalScore / stored.size
    }

    private fun comparePositions(stored: List<RhythmTap>, attempt: List<RhythmTap>): Float {
        var matches = 0

        stored.zip(attempt).forEach { (s, a) ->
            // Check if in same zone (3x3 grid)
            val sZone = getZone(s.x, s.y)
            val aZone = getZone(a.x, a.y)

            if (sZone == aZone) matches++
        }

        return matches.toFloat() / stored.size
    }

    private fun getZone(x: Float, y: Float): Int {
        val col = (x * 3).toInt().coerceIn(0, 2)
        val row = (y * 3).toInt().coerceIn(0, 2)
        return row * 3 + col // 0-8
    }
}

data class MatchResult(
    val confidence: Float,
    val isMatch: Boolean,
    val details: MatchDetails
)

sealed class MatchDetails {
    data class TapCountMismatch(val expected: Int, val actual: Int) : MatchDetails()
    data class Scored(val intervalScore: Float, val positionScore: Float) : MatchDetails()
}
