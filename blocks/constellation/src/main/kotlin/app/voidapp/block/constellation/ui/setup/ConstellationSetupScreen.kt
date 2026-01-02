package app.voidapp.block.constellation.ui.setup

import android.graphics.Bitmap
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
import app.voidapp.block.constellation.security.ConstellationSecurityManager
import app.voidapp.block.constellation.ui.components.ConstellationView
import app.voidapp.block.constellation.ui.components.PatternQualityIndicator
import org.koin.androidx.compose.koinViewModel

/**
 * Screen for creating a new constellation pattern.
 *
 * Flow:
 * 1. User taps 4-6 landmarks in sequence (V2)
 * 2. Quality indicator shows pattern strength
 * 3. User proceeds to confirmation screen
 */
@Composable
fun ConstellationSetupScreen(
    onComplete: (LandmarkPattern, List<StarGenerator.Landmark>, StarGenerator.GenerationMetadata?, Bitmap, Int, Int) -> Unit,  // V2: Updated signature
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConstellationSetupViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Get screen dimensions
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx().toInt() }

    // Regenerate constellation with actual screen size
    LaunchedEffect(screenWidth, screenHeight) {
        if (screenWidth > 0 && screenHeight > 0) {
            viewModel.generateConstellation(screenWidth, screenHeight)
        }
    }

    when (val currentState = state) {
        is ConstellationSetupState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is ConstellationSetupState.Ready -> {
            ConstellationSetupContent(
                state = currentState,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                onStarTapped = { tap ->
                    viewModel.onStarTapped(tap, screenWidth, screenHeight)
                },
                onReset = { viewModel.onReset() },
                onProceed = {
                    viewModel.onProceed(screenWidth, screenHeight)
                },
                onCancel = onCancel,
                modifier = modifier
            )
        }

        is ConstellationSetupState.PatternCreated -> {
            println("VOID_DEBUG: ConstellationSetupScreen onComplete called")
            LaunchedEffect(currentState) {
                onComplete(
                    currentState.pattern,         // V2: LandmarkPattern
                    currentState.landmarks,       // V2: Pass landmarks
                    currentState.metadata,        // V2: Pass metadata
                    currentState.constellation,   // Pass exact bitmap
                    currentState.screenWidth,     // Lock dimensions
                    currentState.screenHeight
                )
            }
        }

        is ConstellationSetupState.Error -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentState.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onCancel) {
                        Text("Go Back")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConstellationSetupContent(
    state: ConstellationSetupState.Ready,
    screenWidth: Int,
    screenHeight: Int,
    onStarTapped: (app.voidapp.block.constellation.domain.TapPoint) -> Unit,
    onReset: () -> Unit,
    onProceed: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Main content - FIXED positions
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
                    text = "Create Your Constellation Pattern",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Tap ${state.requiredStars}-${ConstellationSecurityManager.MAX_STARS} stars in sequence",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Progress dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(ConstellationSecurityManager.MAX_STARS) { index ->
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

            // Constellation view - FIXED space, takes remaining vertical space
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
                    OutlinedButton(
                        onClick = if (state.tappedStars.isEmpty()) onCancel else onReset
                    ) {
                        Text(if (state.tappedStars.isEmpty()) "Cancel" else "Reset")
                    }

                    Button(
                        onClick = onProceed,
                        enabled = state.canProceed
                    ) {
                        Text("Continue")
                    }
                }
            }
        }

        // Quality indicator overlay - doesn't affect constellation position
        if (state.tappedStars.isNotEmpty()) {
            PatternQualityIndicator(
                quality = state.patternQuality,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 160.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth()
            )
        }
    }
}
