package app.voidapp.block.constellation.ui.confirm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.voidapp.block.constellation.domain.LandmarkPattern
import app.voidapp.block.constellation.domain.StarGenerator
import app.voidapp.block.constellation.ui.components.ConstellationView
import org.koin.androidx.compose.koinViewModel

/**
 * Screen for confirming the constellation pattern.
 * User must recreate the same pattern to proceed.
 */
@Composable
fun ConstellationConfirmScreen(
    firstPattern: LandmarkPattern,  // V2: Use LandmarkPattern
    landmarks: List<StarGenerator.Landmark>,  // V2: Pass landmarks
    metadata: StarGenerator.GenerationMetadata?,  // V2: Pass metadata
    constellation: android.graphics.Bitmap,  // Reuse exact same bitmap from setup
    screenWidth: Int,                         // Locked dimensions from setup
    screenHeight: Int,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConstellationConfirmViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Initialize with the SAME constellation bitmap from setup (no regeneration!)
    LaunchedEffect(firstPattern) {
        viewModel.initialize(firstPattern, landmarks, constellation, metadata, screenWidth, screenHeight)  // V2: Pass landmarks and metadata
    }

    when (val currentState = state) {
        is ConstellationConfirmState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is ConstellationConfirmState.Ready -> {
            ConstellationConfirmContent(
                state = currentState,
                onStarTapped = { tap ->
                    viewModel.onStarTapped(tap, screenWidth, screenHeight)
                },
                onReset = { viewModel.onReset() },
                onCancel = onCancel,
                onConfirm = {
                    viewModel.confirmPattern(screenWidth, screenHeight)
                },
                modifier = modifier
            )
        }

        is ConstellationConfirmState.Success -> {
            println("VOID_DEBUG: ConstellationConfirmScreen - Success state reached!")
            LaunchedEffect(Unit) {
                println("VOID_DEBUG: Calling onSuccess() to navigate")
                onSuccess()
                println("VOID_DEBUG: onSuccess() call completed")
            }
        }

        is ConstellationConfirmState.Error -> {
            // Legacy error state - should not be used anymore
            // Kept for compatibility but errors now shown in Ready state
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = currentState.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onCancel) {
                        Text("Start Over")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConstellationConfirmContent(
    state: ConstellationConfirmState.Ready,
    onStarTapped: (app.voidapp.block.constellation.domain.TapPoint) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPatternComplete = state.tappedStars.size == state.firstPattern.landmarkIndices.size  // V2: Use landmarkIndices
    val errorMessage = state.errorMessage

    Box(modifier = modifier.fillMaxSize()) {
        // Main content - FIXED position
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header - FIXED height
            Column(
                modifier = Modifier.height(140.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Confirm Your Pattern",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Tap the same ${state.firstPattern.landmarkIndices.size} landmarks in the same order",  // V2: Use landmarkIndices
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Progress dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(state.firstPattern.landmarkIndices.size) { index ->  // V2: Use landmarkIndices
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = if (index < state.tappedStars.size)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }

            // Constellation view - FIXED space, takes all remaining vertical space
            ConstellationView(
                constellation = state.constellation,
                tappedPoints = state.tappedStars,
                onTap = { tap, _, _ -> onStarTapped(tap) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Bottom actions - FIXED height
            Column(
                modifier = Modifier.height(80.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.tappedStars.isNotEmpty()) {
                            OutlinedButton(onClick = onReset) {
                                Text("Reset")
                            }
                        }

                        // Show Confirm button when pattern is complete
                        if (isPatternComplete) {
                            Button(
                                onClick = onConfirm
                            ) {
                                Text("Confirm")
                            }
                        }
                    }
                }
            }
        }

        // Error message overlay - doesn't affect constellation position
        if (errorMessage != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 160.dp, start = 24.dp, end = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Success message overlay - doesn't affect constellation position
        if (isPatternComplete && errorMessage == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 160.dp, start = 24.dp, end = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    text = "Pattern complete! Tap Confirm to proceed.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
