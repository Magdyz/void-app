package com.void.block.rhythm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.rhythm.domain.MatchResult
import com.void.block.rhythm.domain.RhythmCapture
import com.void.block.rhythm.domain.RhythmMatcher
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.ui.components.RhythmPad
import com.void.block.rhythm.ui.components.TapIndicator

/**
 * Screen for confirming the rhythm pattern.
 *
 * User must repeat the pattern with sufficient accuracy.
 * Uses fuzzy matching to allow natural variation.
 */
@Composable
fun RhythmConfirmScreen(
    originalPattern: RhythmPattern,
    onConfirmed: (RhythmPattern) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capture = remember { RhythmCapture() }
    val tapCount by capture.tapCount.collectAsState()
    val matcher = remember { RhythmMatcher() }
    var matchResult by remember { mutableStateOf<MatchResult?>(null) }

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
            text = "Confirm Your Rhythm",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Repeat the same pattern to confirm.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Match result feedback
        matchResult?.let { result ->
            MatchFeedback(result = result)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Tap indicator (must match original count)
        TapIndicator(
            tapCount = tapCount,
            minTaps = originalPattern.taps.size,
            maxTaps = originalPattern.taps.size
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Rhythm pad
        RhythmPad(
            onTap = { pressure, x, y, duration ->
                capture.recordTap(pressure, x, y, duration)

                // Auto-check when tap count matches
                if (tapCount + 1 == originalPattern.taps.size) {
                    capture.finish()?.let { attempt ->
                        val result = matcher.match(originalPattern, attempt)
                        matchResult = result

                        if (result.isMatch) {
                            // Success! Pass the original pattern (not the attempt)
                            onConfirmed(originalPattern)
                        }
                    }
                }
            },
            enabled = matchResult?.isMatch != true,
            promptText = when {
                matchResult?.isMatch == true -> "Pattern confirmed!"
                tapCount == 0 -> "Tap to confirm"
                else -> "Keep tapping..."
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons based on state
        when {
            matchResult?.isMatch == true -> {
                Button(
                    onClick = { onConfirmed(originalPattern) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue")
                }
            }
            matchResult != null -> {
                // Failed match
                Column {
                    Text(
                        text = "Pattern didn't match closely enough. Try again!",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start Over")
                        }
                        Button(
                            onClick = {
                                matchResult = null
                                capture.cancel()
                                capture.start()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info text
        if (matchResult == null) {
            Text(
                text = "Don't worry about being perfect—natural variations are OK!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MatchFeedback(result: MatchResult) {
    val (icon, color, text) = when {
        result.isMatch -> Triple(
            Icons.Default.Check,
            MaterialTheme.colorScheme.primary,
            "Perfect match! ✓"
        )
        result.confidence > 0.5f -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Close, but try to match the timing more precisely."
        )
        else -> Triple(
            Icons.Default.Close,
            MaterialTheme.colorScheme.error,
            "Pattern didn't match. Try again."
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
