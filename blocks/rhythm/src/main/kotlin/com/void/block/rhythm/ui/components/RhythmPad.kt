package com.void.block.rhythm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Large tap area for rhythm input.
 *
 * SECURITY: No visual feedback on taps to prevent shoulder surfing.
 * User must rely on the tap count indicator above.
 */
@Composable
fun RhythmPad(
    onTap: (pressure: Float, x: Float, y: Float, duration: Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    promptText: String = "Tap your rhythm"
) {
    var tapStartTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                tapStartTime = System.currentTimeMillis()

                                // Normalize position (0.0 to 1.0)
                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                val y = (offset.y / size.height).coerceIn(0f, 1f)

                                // Wait for release
                                val released = tryAwaitRelease()

                                if (released) {
                                    val duration = System.currentTimeMillis() - tapStartTime
                                    // Pressure not available in Compose - use 1.0
                                    onTap(1f, x, y, duration)
                                }
                            }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Subtle prompt - no feedback on taps!
        Text(
            text = promptText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}
