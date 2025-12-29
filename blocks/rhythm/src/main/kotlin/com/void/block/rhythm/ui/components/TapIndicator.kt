package com.void.block.rhythm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Shows tap count progress as dots.
 *
 * @param tapCount Current number of taps
 * @param minTaps Minimum required taps (shown as larger dots)
 * @param maxTaps Maximum allowed taps
 */
@Composable
fun TapIndicator(
    tapCount: Int,
    minTaps: Int = 4,
    maxTaps: Int = 12,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(maxTaps) { index ->
            val isFilled = index < tapCount
            val isRequired = index < minTaps

            Box(
                modifier = Modifier
                    .size(if (isRequired) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isFilled -> MaterialTheme.colorScheme.primary
                            isRequired -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}
