package com.void.block.contacts.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
                .padding(24.dp)
        ) {
            // WORD 01 Input
            Text(
                text = "WORD 01",
                style = TerminalStandard.Body,
                color = TerminalStandard.Text
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = identityInput.split(".").getOrNull(0) ?: "",
                onValueChange = { newWord ->
                    val parts = identityInput.split(".")
                    val word2 = parts.getOrNull(1) ?: ""
                    val word3 = parts.getOrNull(2) ?: ""
                    viewModel.onIdentityChanged(buildString {
                        append(newWord)
                        if (word2.isNotEmpty() || word3.isNotEmpty()) append(".$word2")
                        if (word3.isNotEmpty()) append(".$word3")
                    })
                },
                placeholder = {
                    Text(
                        text = "[___________]",
                        style = TerminalStandard.Input,
                        color = TerminalStandard.TextSecondary
                    )
                },
                textStyle = TerminalStandard.Input,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TerminalStandard.Text,
                    unfocusedBorderColor = TerminalStandard.Border,
                    focusedTextColor = TerminalStandard.Text,
                    unfocusedTextColor = TerminalStandard.Text,
                    cursorColor = TerminalStandard.Text,
                    focusedContainerColor = TerminalStandard.Background,
                    unfocusedContainerColor = TerminalStandard.Background
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // WORD 02 Input
            Text(
                text = "WORD 02",
                style = TerminalStandard.Body,
                color = TerminalStandard.Text
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = identityInput.split(".").getOrNull(1) ?: "",
                onValueChange = { newWord ->
                    val parts = identityInput.split(".")
                    val word1 = parts.getOrNull(0) ?: ""
                    val word3 = parts.getOrNull(2) ?: ""
                    viewModel.onIdentityChanged(buildString {
                        append(word1)
                        append(".")
                        append(newWord)
                        if (word3.isNotEmpty()) append(".$word3")
                    })
                },
                placeholder = {
                    Text(
                        text = "[___________]",
                        style = TerminalStandard.Input,
                        color = TerminalStandard.TextSecondary
                    )
                },
                textStyle = TerminalStandard.Input,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TerminalStandard.Text,
                    unfocusedBorderColor = TerminalStandard.Border,
                    focusedTextColor = TerminalStandard.Text,
                    unfocusedTextColor = TerminalStandard.Text,
                    cursorColor = TerminalStandard.Text,
                    focusedContainerColor = TerminalStandard.Background,
                    unfocusedContainerColor = TerminalStandard.Background
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // WORD 03 Input
            Text(
                text = "WORD 03",
                style = TerminalStandard.Body,
                color = TerminalStandard.Text
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = identityInput.split(".").getOrNull(2) ?: "",
                onValueChange = { newWord ->
                    val parts = identityInput.split(".")
                    val word1 = parts.getOrNull(0) ?: ""
                    val word2 = parts.getOrNull(1) ?: ""
                    viewModel.onIdentityChanged("$word1.$word2.$newWord")
                },
                placeholder = {
                    Text(
                        text = "[___________]",
                        style = TerminalStandard.Input,
                        color = TerminalStandard.TextSecondary
                    )
                },
                textStyle = TerminalStandard.Input,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TerminalStandard.Text,
                    unfocusedBorderColor = TerminalStandard.Border,
                    focusedTextColor = TerminalStandard.Text,
                    unfocusedTextColor = TerminalStandard.Text,
                    cursorColor = TerminalStandard.Text,
                    focusedContainerColor = TerminalStandard.Background,
                    unfocusedContainerColor = TerminalStandard.Background
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Add contact button
            val allFieldsFilled = identityInput.split(".").size == 3 &&
                identityInput.split(".").all { it.isNotBlank() }

            TextButton(
                onClick = { viewModel.addContact() },
                enabled = allFieldsFilled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (allFieldsFilled) TerminalStandard.Text else TerminalStandard.Disabled,
                    contentColor = TerminalStandard.Background,
                    disabledContainerColor = TerminalStandard.Disabled,
                    disabledContentColor = TerminalStandard.TextSecondary
                )
            ) {
                Text(
                    text = if (allFieldsFilled) {
                        TerminalStandard.bracketLabel("CONNECT")
                    } else {
                        TerminalStandard.bracketLabel("ENTER ID")
                    },
                    style = TerminalStandard.Button
                )
            }
        }
    }
}
