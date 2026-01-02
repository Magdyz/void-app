package app.voidapp.block.constellation.domain

/**
 * Quantizes star positions to a fixed grid for consistent pattern matching.
 * Uses a 24x24 grid for generous tap tolerance (~4% screen per cell).
 * With 20 shapes and 3-5 taps, provides millions of possible combinations.
 */
class StarQuantizer {
    companion object {
        const val GRID_SIZE = 24  // Much more forgiving - ~4% screen tolerance
    }

    /**
     * Quantize a normalized star point to a grid cell.
     */
    fun quantize(point: StarPoint): QuantizedPoint {
        val cellX = (point.normalizedX * GRID_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
        val cellY = (point.normalizedY * GRID_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
        return QuantizedPoint(cellX, cellY)
    }

    /**
     * Normalize a raw tap to a device-independent star point.
     */
    fun normalize(tap: TapPoint, screenWidth: Int, screenHeight: Int): StarPoint {
        return StarPoint(
            normalizedX = (tap.x / screenWidth).coerceIn(0f, 1f),
            normalizedY = (tap.y / screenHeight).coerceIn(0f, 1f)
        )
    }

    /**
     * Batch normalize multiple taps.
     */
    fun normalizeAll(
        taps: List<TapPoint>,
        screenWidth: Int,
        screenHeight: Int
    ): List<StarPoint> {
        return taps.map { normalize(it, screenWidth, screenHeight) }
    }
}
