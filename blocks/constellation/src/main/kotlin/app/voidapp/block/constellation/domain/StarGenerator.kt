package app.voidapp.block.constellation.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.void.slate.crypto.CryptoProvider
import kotlin.random.Random

/**
 * Generates deterministic constellation patterns from identity seeds.
 * CRITICAL: Same seed MUST produce identical visual across all devices.
 */
class StarGenerator(
    private val crypto: CryptoProvider
) {
    companion object {
        const val ALGORITHM_VERSION = 3  // Gravity well + landmark system
        const val LANDMARK_COUNT = 8  // One per unique shape - no duplicates!
        const val CONNECTION_DENSITY = 0.12f  // Light connections for visual interest
        const val MAX_SNAP_DISTANCE = 300f  // Max distance in pixels to snap to landmark
    }

    enum class LandmarkShape {
        TRIANGLE, HEXAGON, STAR, CROSS, DIAMOND, RING, SQUARE, PENTAGON
    }

    data class Landmark(
        val id: Int,              // Unique ID for this landmark (0-15)
        val shape: LandmarkShape,
        val color: Int,
        val x: Float,             // Absolute pixel position
        val y: Float,
        val normalizedX: Float,   // Normalized 0-1 for device independence
        val normalizedY: Float,
        val size: Float
    )

    data class GenerationResult(
        val bitmap: Bitmap,
        val landmarks: List<Landmark>,
        val metadata: GenerationMetadata
    )

    data class GenerationMetadata(
        val algorithmVersion: Int = ALGORITHM_VERSION,
        val verificationHash: String,
        val generatedAt: Long = System.currentTimeMillis()
    )

    /**
     * Generate a deterministic constellation bitmap with landmarks.
     * @param identitySeed The user's identity seed
     * @param width Screen width in pixels
     * @param height Screen height in pixels
     * @return GenerationResult with bitmap, landmarks, and metadata
     */
    suspend fun generate(
        identitySeed: ByteArray,
        width: Int,
        height: Int
    ): GenerationResult {
        // Derive deterministic seed from identity
        val constellationSeed = crypto.derive(identitySeed, "m/constellation/0")
        val seedLong = deriveSeedLong(constellationSeed)
        val random = Random(seedLong)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Dark background
        canvas.drawColor(Color.parseColor("#0a0a0f"))

        // Generate landmarks (distinct shapes with IDs)
        val landmarks = generateLandmarks(random, constellationSeed, width, height)

        // Extract position pairs for connections
        val nodes = landmarks.map { Pair(it.x, it.y) }
        val normalizedNodes = landmarks.map { Pair(it.normalizedX, it.normalizedY) }

        // Draw layers in order: connections → landmarks
        drawConnections(canvas, nodes, random, width)
        drawLandmarks(canvas, landmarks)

        // Generate verification hash
        val verificationHash = generateVerificationHash(constellationSeed, normalizedNodes)

        val metadata = GenerationMetadata(
            verificationHash = verificationHash
        )

        return GenerationResult(
            bitmap = bitmap,
            landmarks = landmarks,
            metadata = metadata
        )
    }

    /**
     * Deterministic seed derivation - explicit algorithm that won't change.
     * This algorithm must NEVER be modified to ensure cross-version compatibility.
     */
    private fun deriveSeedLong(seed: ByteArray): Long {
        var result = 0L
        for (i in 0 until minOf(8, seed.size)) {
            result = result or ((seed[i].toLong() and 0xFF) shl (i * 8))
        }
        return result
    }

    /**
     * Generate verification hash to detect algorithm changes.
     * Store during setup, verify on each unlock.
     */
    private suspend fun generateVerificationHash(
        seed: ByteArray,
        nodes: List<Pair<Float, Float>>
    ): String {
        val input = buildString {
            append("v$ALGORITHM_VERSION:")
            append(seed.take(16).joinToString("") { "%02x".format(it) })
            append(":")
            nodes.take(10).forEach { (x, y) ->
                append("%.4f,%.4f;".format(x, y))
            }
        }
        val hash = crypto.hash(input.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Draw connection lines between nodes.
     */
    private fun drawConnections(
        canvas: Canvas,
        nodes: List<Pair<Float, Float>>,
        random: Random,
        width: Int
    ) {
        val linePaint = Paint().apply {
            color = Color.parseColor("#1a1a2e")
            strokeWidth = 1.5f * (width / 1080f)
            style = Paint.Style.STROKE
            alpha = 60
            isAntiAlias = true
        }

        nodes.forEachIndexed { i, _ ->
            nodes.drop(i + 1).forEachIndexed { j, _ ->
                if (random.nextFloat() < CONNECTION_DENSITY) {
                    canvas.drawLine(
                        nodes[i].first, nodes[i].second,
                        nodes[i + j + 1].first, nodes[i + j + 1].second,
                        linePaint
                    )
                }
            }
        }
    }

    /**
     * Generate landmarks with distinct types and colors.
     * Ensures minimum spacing between landmarks for easy tapping.
     */
    private fun generateLandmarks(
        random: Random,
        seed: ByteArray,
        width: Int,
        height: Int
    ): List<Landmark> {
        val scale = width / 1080f
        val minSpacing = (width * 0.15f)  // 15% of screen width minimum spacing

        // Create vibrant, distinct color palette from seed
        val hueBase = (seed[0].toInt() and 0xFF) / 255f * 360f
        val colors = listOf(
            Color.HSVToColor(floatArrayOf(hueBase % 360, 0.85f, 0.95f)),           // Vibrant primary
            Color.HSVToColor(floatArrayOf((hueBase + 40) % 360, 0.80f, 1.0f)),     // Orange/yellow
            Color.HSVToColor(floatArrayOf((hueBase + 80) % 360, 0.75f, 0.90f)),    // Green
            Color.HSVToColor(floatArrayOf((hueBase + 120) % 360, 0.85f, 0.95f)),   // Cyan
            Color.HSVToColor(floatArrayOf((hueBase + 160) % 360, 0.80f, 1.0f)),    // Blue
            Color.HSVToColor(floatArrayOf((hueBase + 200) % 360, 0.85f, 0.95f)),   // Purple
            Color.HSVToColor(floatArrayOf((hueBase + 240) % 360, 0.75f, 0.90f)),   // Magenta
            Color.HSVToColor(floatArrayOf((hueBase + 280) % 360, 0.80f, 1.0f)),    // Pink
            Color.HSVToColor(floatArrayOf((hueBase + 320) % 360, 0.85f, 0.95f)),   // Red
        )

        // Use each shape exactly once - 8 unique shapes for 8 landmarks
        val shapeTypes = LandmarkShape.values().toList()

        // Shuffle for randomness but use all 8 shapes
        val selectedShapes = shapeTypes.shuffled(random)

        val landmarks = mutableListOf<Landmark>()
        val padding = 80f * scale

        selectedShapes.forEachIndexed { id, shapeType ->
            // Try to find a position with minimum spacing
            var x: Float
            var y: Float
            var attempts = 0

            do {
                x = padding + random.nextFloat() * (width - 2 * padding)
                y = padding + random.nextFloat() * (height - 2 * padding)
                attempts++

                val tooClose = landmarks.any { existing ->
                    val dx = x - existing.x
                    val dy = y - existing.y
                    kotlin.math.sqrt((dx * dx + dy * dy).toDouble()) < minSpacing
                }
            } while (tooClose && attempts < 50)

            // Size between 70-90px scaled - larger for 16 landmarks
            val size = (70f + random.nextFloat() * 20f) * scale

            landmarks.add(
                Landmark(
                    id = id,
                    x = x,
                    y = y,
                    normalizedX = x / width,
                    normalizedY = y / height,
                    shape = shapeType,
                    color = colors[id % colors.size],
                    size = size
                )
            )
        }

        println("VOID_DEBUG: Generated ${landmarks.size} landmarks:")
        landmarks.forEach { landmark ->
            println("VOID_DEBUG:   Landmark #${landmark.id}: ${landmark.shape} at (${landmark.x.toInt()}, ${landmark.y.toInt()}) size=${landmark.size.toInt()}px")
        }

        return landmarks
    }

    /**
     * Draw landmarks (distinct shapes).
     */
    private fun drawLandmarks(canvas: Canvas, landmarks: List<Landmark>) {
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        landmarks.forEach { landmark ->
            fillPaint.color = landmark.color
            strokePaint.color = landmark.color

            when (landmark.shape) {
                LandmarkShape.TRIANGLE -> drawTriangle(canvas, landmark, fillPaint)
                LandmarkShape.HEXAGON -> drawHexagon(canvas, landmark, fillPaint)
                LandmarkShape.STAR -> drawStar(canvas, landmark, fillPaint)
                LandmarkShape.CROSS -> drawCross(canvas, landmark, fillPaint)
                LandmarkShape.DIAMOND -> drawDiamond(canvas, landmark, fillPaint)
                LandmarkShape.RING -> drawRing(canvas, landmark, strokePaint)
                LandmarkShape.SQUARE -> drawSquare(canvas, landmark, fillPaint)
                LandmarkShape.PENTAGON -> drawPentagon(canvas, landmark, fillPaint)
            }
        }
    }

    private fun drawTriangle(canvas: Canvas, landmark: Landmark, paint: Paint) {
        val path = Path().apply {
            moveTo(landmark.x, landmark.y - landmark.size)
            lineTo(landmark.x + landmark.size * 0.866f, landmark.y + landmark.size * 0.5f)
            lineTo(landmark.x - landmark.size * 0.866f, landmark.y + landmark.size * 0.5f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawHexagon(canvas: Canvas, landmark: Landmark, paint: Paint) {
        val path = Path()
        val angles = 6
        for (i in 0 until angles) {
            val angle = (i * 60 - 30) * Math.PI / 180
            val x = landmark.x + landmark.size * kotlin.math.cos(angle).toFloat()
            val y = landmark.y + landmark.size * kotlin.math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawStar(canvas: Canvas, landmark: Landmark, paint: Paint) {
        val path = Path()
        val points = 5
        val outerRadius = landmark.size
        val innerRadius = landmark.size * 0.4f

        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = (i * 36 - 90) * Math.PI / 180
            val x = landmark.x + radius * kotlin.math.cos(angle).toFloat()
            val y = landmark.y + radius * kotlin.math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawCross(canvas: Canvas, landmark: Landmark, paint: Paint) {
        val thickness = landmark.size * 0.3f
        val path = Path().apply {
            // Horizontal bar
            moveTo(landmark.x - landmark.size, landmark.y - thickness)
            lineTo(landmark.x + landmark.size, landmark.y - thickness)
            lineTo(landmark.x + landmark.size, landmark.y + thickness)
            lineTo(landmark.x - landmark.size, landmark.y + thickness)
            close()

            // Vertical bar
            moveTo(landmark.x - thickness, landmark.y - landmark.size)
            lineTo(landmark.x + thickness, landmark.y - landmark.size)
            lineTo(landmark.x + thickness, landmark.y + landmark.size)
            lineTo(landmark.x - thickness, landmark.y + landmark.size)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawDiamond(canvas: Canvas, landmark: Landmark, paint: Paint) {
        val path = Path().apply {
            moveTo(landmark.x, landmark.y - landmark.size)
            lineTo(landmark.x + landmark.size, landmark.y)
            lineTo(landmark.x, landmark.y + landmark.size)
            lineTo(landmark.x - landmark.size, landmark.y)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawRing(canvas: Canvas, landmark: Landmark, paint: Paint) {
        paint.strokeWidth = landmark.size * 0.25f
        canvas.drawCircle(landmark.x, landmark.y, landmark.size * 0.7f, paint)
    }

    private fun drawSquare(canvas: Canvas, landmark: Landmark, paint: Paint) {
        canvas.drawRect(
            landmark.x - landmark.size * 0.7f,
            landmark.y - landmark.size * 0.7f,
            landmark.x + landmark.size * 0.7f,
            landmark.y + landmark.size * 0.7f,
            paint
        )
    }

    private fun drawPentagon(canvas: Canvas, landmark: Landmark, paint: Paint) {
        val path = Path()
        val points = 5
        for (i in 0 until points) {
            val angle = (i * 72 - 90) * Math.PI / 180
            val x = landmark.x + landmark.size * kotlin.math.cos(angle).toFloat()
            val y = landmark.y + landmark.size * kotlin.math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

}
