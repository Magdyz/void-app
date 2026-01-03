package app.voidapp.block.constellation.ui.biometric

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.koin.androidx.compose.koinViewModel

/**
 * Screen for setting up biometric authentication.
 * Flow: Enroll biometric → User will setup backup pattern in next screen
 */
@Composable
fun BiometricSetupScreen(
    onBiometricEnrolled: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BiometricSetupViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        when (val currentState = state) {
            is BiometricSetupState.Initial -> {
                BiometricExplainerContent(
                    onProceed = {
                        println("VOID_DEBUG: BiometricSetupScreen onProceed called")
                        val activity = context as? FragmentActivity
                        println("VOID_DEBUG: Activity cast result: ${if (activity != null) "success" else "null"}")
                        if (activity != null) {
                            println("VOID_DEBUG: Calling viewModel.startBiometricEnrollment")
                            viewModel.startBiometricEnrollment(activity)
                        } else {
                            println("VOID_DEBUG: ERROR - Context is not a FragmentActivity!")
                        }
                    },
                    onCancel = onCancel
                )
            }

            is BiometricSetupState.Authenticating -> {
                BiometricPromptContent()
            }

            is BiometricSetupState.Success -> {
                BiometricSuccessContent(
                    onProceedToBackup = onBiometricEnrolled
                )
            }

            is BiometricSetupState.Error -> {
                BiometricErrorContent(
                    error = currentState.message,
                    onRetry = {
                        val activity = context as? FragmentActivity
                        if (activity != null) {
                            viewModel.startBiometricEnrollment(activity)
                        }
                    },
                    onCancel = onCancel
                )
            }
        }
    }
}

@Composable
private fun BiometricExplainerContent(
    onProceed: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        // Icon
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Title
        Text(
            text = "Setup Biometric Unlock",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = "Use your fingerprint or face to unlock VOID quickly and securely.\n\nYou'll also create a backup pattern in case biometric fails.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(0.7f))

        // Actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Button(
                onClick = {
                    println("VOID_DEBUG: Setup Biometric button clicked")
                    onProceed()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Setup Biometric")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun BiometricPromptContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Waiting for biometric...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BiometricSuccessContent(
    onProceedToBackup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        // Success icon
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Biometric Enrolled",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Now create a backup pattern for situations where biometric doesn't work.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(0.7f))

        Button(
            onClick = onProceedToBackup,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Create Backup Pattern")
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun BiometricErrorContent(
    error: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        Text(
            text = "Biometric Setup Failed",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(0.7f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Try Again")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}
