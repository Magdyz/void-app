package app.voidapp.block.constellation.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.voidapp.block.constellation.security.BiometricAvailability
import org.koin.androidx.compose.koinViewModel

enum class AuthMethod {
    CONSTELLATION,
    BIOMETRIC
}

/**
 * Screen for selecting authentication method during first setup.
 * User chooses between Constellation pattern or Biometric unlock.
 */
@Composable
fun AuthMethodSelectionScreen(
    onMethodSelected: (AuthMethod) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthMethodSelectionViewModel = koinViewModel()
) {
    val biometricAvailability by viewModel.biometricAvailability.collectAsState()
    var selectedMethod by remember { mutableStateOf<AuthMethod?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
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
                    text = "Choose Unlock Method",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Select how you want to unlock VOID",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Method options
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Constellation option
                AuthMethodCard(
                    title = "Constellation Pattern",
                    subtitle = "Tap landmarks in sequence",
                    icon = Icons.Default.Star,
                    selected = selectedMethod == AuthMethod.CONSTELLATION,
                    onClick = { selectedMethod = AuthMethod.CONSTELLATION }
                )

                // Biometric option
                val biometricEnabled = biometricAvailability == BiometricAvailability.Available
                AuthMethodCard(
                    title = "Biometric",
                    subtitle = when (biometricAvailability) {
                        BiometricAvailability.Available -> "Fingerprint or face recognition"
                        BiometricAvailability.NotEnrolled -> "No biometric enrolled"
                        BiometricAvailability.NoHardware -> "Not available on this device"
                        else -> "Not available"
                    },
                    icon = Icons.Default.Lock,
                    selected = selectedMethod == AuthMethod.BIOMETRIC,
                    enabled = biometricEnabled,
                    onClick = { selectedMethod = AuthMethod.BIOMETRIC }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom actions - FIXED position
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Continue button
                Button(
                    onClick = { selectedMethod?.let(onMethodSelected) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = selectedMethod != null,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Continue",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Cancel button
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthMethodCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.onBackground
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    OutlinedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = borderColor
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surface
        ),
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (selected)
                    MaterialTheme.colorScheme.primary
                else
                    contentColor
            )

            Spacer(modifier = Modifier.width(20.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }

            // Selection indicator
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
