package com.void.block.rhythm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.rhythm.domain.RhythmCapture
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.ui.components.RhythmPad
import com.void.block.rhythm.ui.components.TapIndicator

/**
 * Screen for creating a new rhythm pattern.
 *
 * Flow:
 * 1. User taps a pattern (4-12 taps)
 * 2. User can start over if they make a mistake
 * 3. User continues to confirmation screen
 */
@Composable
fun RhythmSetupScreen(
    onComplete: (RhythmPattern) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capture = remember { RhythmCapture() }
    val tapCount by capture.tapCount.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        capture.start()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Create Your Rhythm Key",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Tap a memorable pattern—like a song beat, morse code, or a personal sequence.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Minimum ${RhythmPattern.MIN_TAPS} taps, maximum ${RhythmPattern.MAX_TAPS}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Tap indicator
        TapIndicator(
            tapCount = tapCount,
            minTaps = RhythmPattern.MIN_TAPS,
            maxTaps = RhythmPattern.MAX_TAPS
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Rhythm pad
        RhythmPad(
            onTap = { pressure, x, y, duration ->
                val success = capture.recordTap(pressure, x, y, duration)
                if (!success) {
                    errorMessage = "Pattern too long or timed out"
                }
            },
            promptText = if (tapCount == 0) "Tap to begin" else "Keep tapping..."
        )

        // Error message
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = {
                    capture.cancel()
                    capture.start()
                    errorMessage = null
                },
                modifier = Modifier.weight(1f),
                enabled = tapCount > 0
            ) {
                Text("Start Over")
            }

            Button(
                onClick = {
                    val pattern = capture.finish()
                    if (pattern != null) {
                        onComplete(pattern)
                    } else {
                        errorMessage = "Need at least ${RhythmPattern.MIN_TAPS} taps"
                    }
                },
                enabled = tapCount >= RhythmPattern.MIN_TAPS,
                modifier = Modifier.weight(1f)
            ) {
                Text("Continue")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cancel button
        TextButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}
