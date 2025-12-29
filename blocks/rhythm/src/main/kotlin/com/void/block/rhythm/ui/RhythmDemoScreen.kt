package com.void.block.rhythm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.security.UnlockResult

/**
 * Demo screen to test all Rhythm UI components.
 *
 * This shows a simple navigation flow through:
 * 1. Setup Screen
 * 2. Confirm Screen
 * 3. Recovery Phrase Screen
 * 4. Unlock Screen
 */
@Composable
fun RhythmDemoScreen() {
    var currentScreen by remember { mutableStateOf<RhythmDemoState>(RhythmDemoState.Menu) }
    var savedPattern by remember { mutableStateOf<RhythmPattern?>(null) }
    var recoveryPhrase by remember { mutableStateOf<List<String>>(emptyList()) }
    var unlockState by remember { mutableStateOf<UnlockResult?>(null) }

    when (val screen = currentScreen) {
        RhythmDemoState.Menu -> {
            DemoMenuScreen(
                onNavigate = { currentScreen = it }
            )
        }
        RhythmDemoState.Setup -> {
            RhythmSetupScreen(
                onComplete = { pattern ->
                    savedPattern = pattern
                    currentScreen = RhythmDemoState.Confirm
                },
                onCancel = { currentScreen = RhythmDemoState.Menu }
            )
        }
        RhythmDemoState.Confirm -> {
            RhythmConfirmScreen(
                originalPattern = savedPattern!!,
                onConfirmed = { pattern ->
                    // Simulate recovery phrase generation
                    recoveryPhrase = listOf(
                        "ocean", "tiger", "moon", "sunset", "river", "forest",
                        "mountain", "valley", "thunder", "crystal", "shadow", "phoenix"
                    )
                    currentScreen = RhythmDemoState.RecoveryPhrase
                },
                onRetry = { currentScreen = RhythmDemoState.Setup }
            )
        }
        RhythmDemoState.RecoveryPhrase -> {
            RecoveryPhraseScreen(
                recoveryPhrase = recoveryPhrase,
                onConfirmed = { currentScreen = RhythmDemoState.Menu },
                onBack = { currentScreen = RhythmDemoState.Confirm }
            )
        }
        RhythmDemoState.Unlock -> {
            RhythmUnlockScreen(
                onUnlock = { pattern ->
                    // Simulate unlock result
                    unlockState = UnlockResult.Success(
                        mode = com.void.block.rhythm.security.UnlockMode.REAL,
                        identitySeed = ByteArray(16),
                        confidence = 1.0f
                    )
                },
                unlockState = unlockState,
                onForgot = { currentScreen = RhythmDemoState.Menu }
            )
        }
    }
}

@Composable
private fun DemoMenuScreen(
    onNavigate: (RhythmDemoState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Rhythm UI Demo",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Test all Rhythm authentication screens:",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onNavigate(RhythmDemoState.Setup) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("1. Setup Screen")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onNavigate(RhythmDemoState.Unlock) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2. Unlock Screen")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { onNavigate(RhythmDemoState.RecoveryPhrase) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Preview: Recovery Phrase")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Full Flow: Setup → Confirm → Recovery Phrase",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

sealed class RhythmDemoState {
    object Menu : RhythmDemoState()
    object Setup : RhythmDemoState()
    object Confirm : RhythmDemoState()
    object RecoveryPhrase : RhythmDemoState()
    object Unlock : RhythmDemoState()
}
