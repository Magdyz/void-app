package com.void.block.rhythm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.rhythm.domain.RhythmCapture
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.security.UnlockMode
import com.void.block.rhythm.security.UnlockResult
import com.void.block.rhythm.ui.components.RhythmPad
import kotlinx.coroutines.delay

/**
 * Screen for unlocking the app with rhythm.
 *
 * Shows:
 * - Lock icon
 * - Tap area
 * - Error messages for failed attempts
 * - Lockout timer
 * - Forgot rhythm link
 */
@Composable
fun RhythmUnlockScreen(
    onUnlock: (RhythmPattern) -> Unit,
    unlockState: UnlockResult?,
    onForgot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capture = remember { RhythmCapture() }
    val tapCount by capture.tapCount.collectAsState()
    var showDoneButton by remember { mutableStateOf(false) }

    // Auto-show done button after minimum taps
    LaunchedEffect(tapCount) {
        if (tapCount >= RhythmPattern.MIN_TAPS) {
            delay(300) // Small delay for better UX
            showDoneButton = true
        }
    }

    // Reset on unlock state change
    LaunchedEffect(unlockState) {
        if (unlockState is UnlockResult.Failed || unlockState is UnlockResult.LockedOut) {
            capture.cancel()
            capture.start()
            showDoneButton = false
        }
    }

    LaunchedEffect(Unit) {
        capture.start()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // VOID logo/lock icon
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status message
            when (val state = unlockState) {
                is UnlockResult.Success -> {
                    Text(
                        text = "Unlocked!",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is UnlockResult.Failed -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Incorrect rhythm",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${state.attemptsRemaining} attempts remaining",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is UnlockResult.LockedOut -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Too many attempts",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Locked for ${state.remainingSeconds} seconds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    Text(
                        text = "Tap to unlock",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Rhythm pad
            RhythmPad(
                onTap = { pressure, x, y, duration ->
                    capture.recordTap(pressure, x, y, duration)
                },
                enabled = unlockState !is UnlockResult.LockedOut,
                modifier = Modifier.fillMaxWidth(),
                promptText = when {
                    unlockState is UnlockResult.LockedOut -> "Locked"
                    tapCount == 0 -> "Tap your rhythm"
                    else -> "Tapping... ($tapCount)"
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Done button (appears after minimum taps)
            if (showDoneButton && unlockState !is UnlockResult.LockedOut) {
                Button(
                    onClick = {
                        capture.finish()?.let { pattern ->
                            onUnlock(pattern)
                            showDoneButton = false
                        }
                    },
                    enabled = tapCount >= RhythmPattern.MIN_TAPS
                ) {
                    Text("Unlock")
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Forgot link
            TextButton(onClick = onForgot) {
                Text("Forgot your rhythm?")
            }

            // Hint text
            Text(
                text = "You can recover access with your 12-word phrase",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
