package com.void.block.contacts.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.void.block.contacts.ui.viewmodels.AddContactUiState
import com.void.block.contacts.ui.viewmodels.AddContactViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Screen for adding a new contact by entering their three-word identity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    onNavigateBack: () -> Unit,
    onNavigateToScanQR: () -> Unit,
    onContactAdded: (String) -> Unit,
    viewModel: AddContactViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val identityInput by viewModel.identityInput.collectAsState()
    val nicknameInput by viewModel.nicknameInput.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Handle UI state changes
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is AddContactUiState.Success -> {
                onContactAdded(state.contactId)
                viewModel.resetState()
            }
            is AddContactUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.resetState()
            }
            AddContactUiState.Input -> {
                // Nothing to do
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Contact") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToScanQR) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Scan QR Code"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            Text(
                text = "Enter Contact Identity",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Ask your contact for their three-word VOID identity",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Three-word identity input
            OutlinedTextField(
                value = identityInput,
                onValueChange = viewModel::onIdentityChanged,
                label = { Text("Three-Word Identity") },
                placeholder = { Text("word1.word2.word3") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        text = "Format: three words separated by dots",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Optional nickname
            OutlinedTextField(
                value = nicknameInput,
                onValueChange = viewModel::onNicknameChanged,
                label = { Text("Nickname (Optional)") },
                placeholder = { Text("Alice") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        text = "A friendly name for this contact",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Add contact button
            Button(
                onClick = { viewModel.addContact() },
                enabled = identityInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Contact")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Scan QR code alternative
            OutlinedButton(
                onClick = onNavigateToScanQR,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.padding(4.dp))
                Text("Scan QR Code Instead")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Help text
            Text(
                text = "About Three-Word Identities",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Every VOID user has a unique three-word identity (like alpha.beta.gamma). " +
                        "This makes it easy to share your identity verbally or in writing. " +
                        "After adding a contact, you'll need to verify their key in person for full security.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
