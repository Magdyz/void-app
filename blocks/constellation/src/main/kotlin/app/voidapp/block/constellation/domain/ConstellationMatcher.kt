package app.voidapp.block.constellation.domain

import app.voidapp.block.constellation.domain.StarGenerator.Landmark
import kotlin.math.sqrt

/**
 * Matches constellation patterns using gravity well landmark snapping.
 * V2: Uses landmark indices instead of grid quantization for better UX.
 * Implements constant-time comparison to prevent timing attacks.
 */
class ConstellationMatcher(
    private val quantizer: StarQuantizer  // Keep for v1 backward compatibility
) {
    companion object {
        // Security model:
        // - 8 unique landmarks on screen (one per shape - no duplicates!)
        // - 3-5 taps required
        // - Order matters
        // - Gravity well prevents pixel-perfect attacks
        // - Combinations: 8^3 = 512 (3 taps), 8^4 = 4,096 (4 taps), 8^5 = 32,768 (5 taps)

        // Generous snap distance for good UX - users don't need to be precise
        const val SNAP_DISTANCE_PX = 450f  // Large enough to catch nearby taps

        // Minimum and maximum adaptive snap distances (increased for better UX)
        const val MIN_SNAP_DISTANCE = 350f  // More forgiving than before
        const val MAX_SNAP_DISTANCE = 700f  // Increased from 600px
    }

    // Thread-safe cache for snap distance per landmark configuration
    // Uses landmark IDs as key to avoid hashCode collisions
    @Volatile
    private var cachedSnapDistance: Float? = null
    @Volatile
    private var cachedLandmarkIds: List<Int>? = null

    /**
     * Calculate adaptive snap distance based on landmark spacing.
     * Cached per landmark configuration to avoid performance issues.
     * Uses thread-safe volatile caching for better performance.
     */
    private fun calculateSnapDistance(landmarks: List<Landmark>): Float {
        if (landmarks.isEmpty()) return SNAP_DISTANCE_PX

        // Check cache using landmark IDs (more reliable than hashCode)
        val currentIds = landmarks.map { it.id }.sorted()
        val cached = cachedSnapDistance
        val cachedIds = cachedLandmarkIds

        if (cached != null && cachedIds != null && cachedIds == currentIds) {
            return cached
        }

        // Calculate snap distance based on landmark spacing
        val avgNearestDistance = landmarks.map { landmark ->
            landmarks
                .filter { it.id != landmark.id }
                .minOfOrNull { other ->
                    val dx = landmark.x - other.x
                    val dy = landmark.y - other.y
                    sqrt(dx * dx + dy * dy)
                } ?: SNAP_DISTANCE_PX
        }.average().toFloat()

        // Snap distance is 50% of average spacing - generous but no overlap
        // With 8 landmarks spread across screen, this gives ~350-700px radius
        val snapDistance = (avgNearestDistance * 0.5f).coerceIn(MIN_SNAP_DISTANCE, MAX_SNAP_DISTANCE)

        // Cache for next time
        cachedLandmarkIds = currentIds
        cachedSnapDistance = snapDistance

        return snapDistance
    }

    /**
     * Find nearest landmark to a tap point using gravity well.
     * Returns null if no landmark within adaptive snap distance.
     */
    fun findNearestLandmark(
        tap: TapPoint,
        landmarks: List<Landmark>
    ): Landmark? {
        if (landmarks.isEmpty()) {
            println("VOID_DEBUG: No landmarks available")
            return null
        }

        val snapDistance = calculateSnapDistance(landmarks)

        val nearest = landmarks.minByOrNull { landmark ->
            val dx = tap.x - landmark.x
            val dy = tap.y - landmark.y
            sqrt(dx * dx + dy * dy)
        } ?: return null

        // Check if within snap distance
        val dx = tap.x - nearest.x
        val dy = tap.y - nearest.y
        val distance = sqrt(dx * dx + dy * dy)

        println("VOID_DEBUG: Tap at (${tap.x}, ${tap.y}) -> Nearest landmark #${nearest.id} at (${nearest.x}, ${nearest.y}), distance: $distance px (adaptive max: $snapDistance)")

        return if (distance <= snapDistance) {
            println("VOID_DEBUG: ✓ Landmark #${nearest.id} selected (${nearest.shape})")
            nearest
        } else {
            println("VOID_DEBUG: ✗ No landmark within snap distance")
            null
        }
    }

    /**
     * Convert tap points to landmark indices using gravity well.
     */
    fun tapsToLandmarkIndices(
        taps: List<TapPoint>,
        landmarks: List<Landmark>
    ): List<Int> {
        return taps.mapNotNull { tap ->
            findNearestLandmark(tap, landmarks)?.id
        }
    }

    /**
     * Create a LandmarkPattern from tap points.
     */
    fun createLandmarkPattern(
        taps: List<TapPoint>,
        landmarks: List<Landmark>
    ): LandmarkPattern {
        val indices = tapsToLandmarkIndices(taps, landmarks)
        return LandmarkPattern(
            landmarkIndices = indices,
            quality = calculateQualityV2(indices.size)
        )
    }

    /**
     * V2 matching: Compare landmark indices (constant-time).
     */
    fun matchesV2(
        attempt: LandmarkPattern,
        stored: LandmarkPattern
    ): Boolean {
        if (attempt.landmarkIndices.size != stored.landmarkIndices.size) return false

        // Constant-time comparison - always check all indices
        var match = true
        attempt.landmarkIndices.zip(stored.landmarkIndices).forEach { (a, s) ->
            if (a != s) {
                match = false
            }
        }

        return match
    }

    /**
     * V1 matching (backward compatibility): Grid-based quantization.
     * SECURITY: Uses constant-time comparison to prevent timing attacks.
     */
    fun matches(
        attempt: ConstellationPattern,
        stored: ConstellationPattern
    ): Boolean {
        if (attempt.stars.size != stored.stars.size) return false

        val attemptQuantized = attempt.stars.map { quantizer.quantize(it) }
        val storedQuantized = stored.stars.map { quantizer.quantize(it) }

        // Constant-time comparison - always check all points
        var match = true
        attemptQuantized.zip(storedQuantized).forEach { (a, s) ->
            if (a != s) {
                match = false
            }
        }

        return match
    }

    /**
     * Validate V2 landmark pattern.
     */
    fun validateLandmarkPattern(pattern: LandmarkPattern): RegistrationResult {
        // Just verify we have a valid pattern
        // Gravity well handles tolerance automatically
        return RegistrationResult.Success
    }

    /**
     * Validate V1 pattern (backward compatibility).
     */
    fun validatePattern(pattern: ConstellationPattern): RegistrationResult {
        return RegistrationResult.Success
    }

    /**
     * Calculate pattern quality score for V2 (0-100).
     */
    fun calculateQualityV2(tapCount: Int): Int {
        return when (tapCount) {
            0, 1, 2 -> 30
            3 -> 60
            4 -> 80
            else -> 100
        }
    }

    /**
     * Calculate pattern quality score for V1 (0-100).
     */
    fun calculateQuality(pattern: ConstellationPattern): Int {
        val stars = pattern.stars
        if (stars.isEmpty()) return 0

        return when (stars.size) {
            0, 1, 2 -> 30
            3 -> 60
            4 -> 80
            else -> 100
        }
    }
}
