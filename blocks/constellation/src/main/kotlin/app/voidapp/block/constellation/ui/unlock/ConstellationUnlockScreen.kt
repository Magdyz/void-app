package app.voidapp.block.constellation.ui.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.voidapp.block.constellation.ui.components.ConstellationView
import org.koin.androidx.compose.koinViewModel
import kotlin.math.max

/**
 * Screen for unlocking with constellation pattern.
 */
@Composable
fun ConstellationUnlockScreen(
    onUnlockSuccess: () -> Unit,
    onForgotPattern: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConstellationUnlockViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Get screen dimensions (remembered to prevent recomposition)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = remember(density, configuration) {
        with(density) { configuration.screenWidthDp.dp.toPx().toInt() }
    }
    val screenHeight = remember(density, configuration) {
        with(density) { configuration.screenHeightDp.dp.toPx().toInt() }
    }

    // Initialize once when dimensions are available
    LaunchedEffect(Unit) {
        if (screenWidth > 0 && screenHeight > 0) {
            viewModel.initialize(screenWidth, screenHeight)
        }
    }

    when (val currentState = state) {
        is ConstellationUnlockState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is ConstellationUnlockState.Ready -> {
            ConstellationUnlockContent(
                state = currentState,
                onStarTapped = { tap ->
                    viewModel.onStarTapped(tap, screenWidth, screenHeight)
                },
                onReset = { viewModel.onReset() },
                onForgotPattern = onForgotPattern,
                modifier = modifier
            )
        }

        is ConstellationUnlockState.Success -> {
            LaunchedEffect(Unit) {
                onUnlockSuccess()
            }
        }

        is ConstellationUnlockState.Failure -> {
            ConstellationFailureContent(
                state = currentState,
                onRetry = { viewModel.onReset() },
                onForgotPattern = onForgotPattern,
                modifier = modifier
            )
        }

        is ConstellationUnlockState.LockedOut -> {
            ConstellationLockedOutContent(
                lockoutEndTime = currentState.lockoutEndTime,
                onForgotPattern = onForgotPattern,
                modifier = modifier
            )
        }

        is ConstellationUnlockState.Error -> {
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
                    Button(onClick = onForgotPattern) {
                        Text("Use Recovery Phrase")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConstellationUnlockContent(
    state: ConstellationUnlockState.Ready,
    onStarTapped: (app.voidapp.block.constellation.domain.TapPoint) -> Unit,
    onReset: () -> Unit,
    onForgotPattern: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Unlock",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Tap your constellation pattern",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.attemptsRemaining < 5) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.attemptsRemaining} attempts remaining",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Constellation view
        ConstellationView(
            constellation = state.constellation,
            tappedPoints = state.tappedStars,
            onTap = { tap, _, _ -> onStarTapped(tap) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onForgotPattern) {
                Text("Forgot pattern?")
            }

            if (state.tappedStars.isNotEmpty()) {
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun ConstellationFailureContent(
    state: ConstellationUnlockState.Failure,
    onRetry: () -> Unit,
    onForgotPattern: () -> Unit,
    modifier: Modifier = Modifier
) {
    // CRITICAL: Show the SAME constellation on failure screen - never regenerate!
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Error message at top
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Show the constellation (same one, not regenerated)
        ConstellationView(
            constellation = state.constellation,
            tappedPoints = emptyList(),  // Don't show previous taps
            onTap = { _, _, _ -> },  // No interaction on failure screen
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Actions
        Button(onClick = onRetry) {
            Text("Try Again")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onForgotPattern) {
            Text("Use Recovery Phrase")
        }
    }
}

@Composable
private fun ConstellationLockedOutContent(
    lockoutEndTime: Long,
    onForgotPattern: () -> Unit,
    modifier: Modifier = Modifier
) {
    var remainingSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(lockoutEndTime) {
        while (true) {
            val now = System.currentTimeMillis()
            remainingSeconds = max(0, (lockoutEndTime - now) / 1000)
            if (remainingSeconds <= 0) break
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Too Many Attempts",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Try again in ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onForgotPattern) {
                Text("Use Recovery Phrase")
            }
        }
    }
}
