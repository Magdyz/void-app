package app.voidapp.block.constellation.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import app.voidapp.block.constellation.domain.TapPoint

/**
 * Composable that displays a constellation and captures tap interactions.
 * Sets FLAG_SECURE to prevent screenshots.
 */
@Composable
fun ConstellationView(
    constellation: Bitmap,
    tappedPoints: List<TapPoint>,
    onTap: (TapPoint, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    privacyMode: Boolean = false
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var screenSize by remember { mutableStateOf(IntSize.Zero) }

    // FLAG_SECURE to prevent screenshots
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { screenSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val tap = TapPoint(offset.x, offset.y)
                    onTap(tap, screenSize.width, screenSize.height)
                }
            }
    ) {
        // Background constellation
        Image(
            bitmap = constellation.asImageBitmap(),
            contentDescription = "Constellation pattern",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds  // Fill without cropping - prevents edge clipping
        )

        // Draw tap indicators (unless privacy mode)
        if (!privacyMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                tappedPoints.forEachIndexed { index, point ->
                    // Inner dot
                    drawCircle(
                        color = Color.White.copy(alpha = 0.7f),
                        radius = 10f,
                        center = Offset(point.x, point.y)
                    )
                    // Outer ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.3f),
                        radius = 22f,
                        center = Offset(point.x, point.y),
                        style = Stroke(width = 2f)
                    )
                    // Sequence number would go here (if desired)
                }
            }
        }
    }
}
