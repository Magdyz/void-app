package com.void.block.contacts.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.contacts.ui.components.ContactItem
import com.void.block.contacts.ui.viewmodels.ContactsViewModel
import com.void.slate.design.theme.TerminalStandard
import org.koin.androidx.compose.koinViewModel

/**
 * Contacts list screen.
 *
 * Shows all contacts and pending requests.
 * Allows navigation to add contact, scan QR, or view contact details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsListScreen(
    onNavigateToAddContact: () -> Unit,
    onNavigateToScanQR: () -> Unit,
    onNavigateToContactDetail: (String) -> Unit,
    viewModel: ContactsViewModel = koinViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val requests by viewModel.contactRequests.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = TerminalStandard.header("CONTACTS"),
                        style = TerminalStandard.Header,
                        color = TerminalStandard.Text
                    )
                },
                actions = {
                    TextButton(onClick = onNavigateToAddContact) {
                        Text(
                            text = TerminalStandard.bracketLabel("+ ADD"),
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
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (contacts.isEmpty() && requests.isEmpty()) {
                // Empty state
                EmptyContactsState(
                    onAddContact = onNavigateToAddContact,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Pending contact requests
                    if (requests.isNotEmpty()) {
                        item {
                            Text(
                                text = "// PENDING (${requests.size})",
                                style = TerminalStandard.Body,
                                color = TerminalStandard.Text,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        items(requests) { request ->
                            ContactRequestItem(
                                request = request,
                                onAccept = { viewModel.acceptContactRequest(request.id) },
                                onReject = { viewModel.rejectContactRequest(request.id) }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    // Contacts list
                    if (contacts.isNotEmpty()) {
                        item {
                            Text(
                                text = "// ALL (${contacts.size})",
                                style = TerminalStandard.Body,
                                color = TerminalStandard.Text,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        items(contacts, key = { it.id }) { contact ->
                            ContactItem(
                                contact = contact,
                                onClick = { onNavigateToContactDetail(contact.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty state when there are no contacts.
 */
@Composable
private fun EmptyContactsState(
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "no contacts",
                style = TerminalStandard.Body,
                color = TerminalStandard.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onAddContact,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = TerminalStandard.Text,
                    contentColor = TerminalStandard.Background
                )
            ) {
                Text(
                    text = TerminalStandard.bracketLabel("+ ADD"),
                    style = TerminalStandard.Button
                )
            }
        }
    }
}

/**
 * Display a pending contact request with accept/reject buttons.
 */
@Composable
private fun ContactRequestItem(
    request: com.void.block.contacts.domain.ContactRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Contact Request",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = request.fromIdentity.toString(),
                style = MaterialTheme.typography.bodyLarge
            )

            if (request.message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = request.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // TODO: Add Accept/Reject buttons when Button composable is available
            // For now, placeholder text
            Text(
                text = "Actions: Accept | Reject",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
