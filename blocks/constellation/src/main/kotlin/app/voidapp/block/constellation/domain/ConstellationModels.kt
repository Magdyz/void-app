package app.voidapp.block.constellation.domain

import kotlinx.serialization.Serializable

/**
 * A normalized star point (0.0-1.0 coordinates).
 * Device-independent representation.
 * DEPRECATED: Used for v1 patterns. New patterns use LandmarkPattern with indices.
 */
@Serializable
data class StarPoint(
    val normalizedX: Float,
    val normalizedY: Float
)

/**
 * A constellation pattern consisting of tapped stars.
 * DEPRECATED: Used for v1 patterns. New patterns use LandmarkPattern with indices.
 */
@Serializable
data class ConstellationPattern(
    val stars: List<StarPoint>,
    val quality: Int,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * V2 constellation pattern using landmark indices.
 * More reliable than coordinate-based matching.
 */
@Serializable
data class LandmarkPattern(
    val version: Int = 2,
    val landmarkIndices: List<Int>,  // IDs of tapped landmarks in order
    val quality: Int = 100,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A quantized point on the 64x64 grid.
 */
@Serializable
data class QuantizedPoint(
    val gridX: Int,
    val gridY: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuantizedPoint) return false
        return gridX == other.gridX && gridY == other.gridY
    }

    override fun hashCode(): Int {
        return 31 * gridX + gridY
    }
}

/**
 * A raw tap point from user interaction.
 */
data class TapPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of constellation unlock attempt.
 */
sealed class ConstellationResult {
    data class Success(val seedHash: ByteArray) : ConstellationResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return seedHash.contentEquals(other.seedHash)
        }

        override fun hashCode(): Int {
            return seedHash.contentHashCode()
        }
    }

    data class Failure(val attemptsRemaining: Int) : ConstellationResult()
    object LockedOut : ConstellationResult()
    object InvalidPattern : ConstellationResult()
    object BiometricCancelled : ConstellationResult()  // User cancelled biometric prompt
}

/**
 * Result of pattern registration.
 */
sealed class RegistrationResult {
    object Success : RegistrationResult()
    data class InvalidQuality(val quality: Int, val minRequired: Int) : RegistrationResult()
    data class PointsTooClose(val minDistance: Float) : RegistrationResult()
    object MismatchWithConfirmation : RegistrationResult()
}
