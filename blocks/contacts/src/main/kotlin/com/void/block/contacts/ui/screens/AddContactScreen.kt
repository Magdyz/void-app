package com.void.block.contacts.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.void.slate.design.theme.TerminalStandard
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
                title = {
                    Text(
                        text = TerminalStandard.header("ADD CONTACT"),
                        style = TerminalStandard.Header,
                        color = TerminalStandard.Text
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(
                            text = TerminalStandard.bracketLabel("<"),
                            style = TerminalStandard.Body,
                            color = TerminalStandard.Text
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TerminalStandard.Background,
                    titleContentColor = TerminalStandard.Text
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Security info
            Text(
                text = "SECURE CONNECTION",
                style = TerminalStandard.Header,
                color = TerminalStandard.Text
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TerminalStandard.Border, RoundedCornerShape(0.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "// To add a contact securely:",
                    style = TerminalStandard.Body,
                    color = TerminalStandard.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. Meet in person",
                    style = TerminalStandard.Body,
                    color = TerminalStandard.Text
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "2. Scan their QR code",
                    style = TerminalStandard.Body,
                    color = TerminalStandard.Text
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "3. Start messaging",
                    style = TerminalStandard.Body,
                    color = TerminalStandard.Text
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Security note
            Text(
                text = "QR codes contain encrypted keys",
                style = TerminalStandard.Body,
                color = TerminalStandard.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "and expire after 5 minutes",
                style = TerminalStandard.Body,
                color = TerminalStandard.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            // Scan QR button
            TextButton(
                onClick = onNavigateToScanQR,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = TerminalStandard.Text,
                    contentColor = TerminalStandard.Background
                )
            ) {
                Text(
                    text = TerminalStandard.bracketLabel("SCAN QR CODE"),
                    style = TerminalStandard.Button
                )
            }
        }
    }
}
