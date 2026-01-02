package app.voidapp.block.constellation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Displays pattern quality score with visual indicator.
 */
@Composable
fun PatternQualityIndicator(
    quality: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        LinearProgressIndicator(
            progress = { quality / 100f },
            color = when {
                quality < 50 -> Color(0xFFE53935) // Red
                quality < 75 -> Color(0xFFFDD835) // Yellow
                else -> Color(0xFF43A047) // Green
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = when {
                quality < 50 -> "Weak pattern - spread stars more"
                quality < 75 -> "Good pattern"
                else -> "Strong pattern"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                quality < 50 -> Color(0xFFE53935)
                quality < 75 -> Color(0xFFFDD835)
                else -> Color(0xFF43A047)
            },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
