package com.void.block.messaging.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.messaging.ui.components.ConversationItem
import com.void.block.messaging.ui.components.IdentityDialog
import com.void.slate.design.theme.TerminalStandard
import org.koin.androidx.compose.koinViewModel

/**
 * Conversation list screen - shows all active conversations.
 *
 * @param userIdentity The user's three-word identity (e.g., "ghost.paper.forty")
 *                     Pass null if identity is not yet available
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationClick: (String) -> Unit,
    onNewConversation: () -> Unit,
    userIdentity: String? = null,
    viewModel: ConversationListViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var showIdentityDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = TerminalStandard.header("INBOX"),
                        style = TerminalStandard.Header,
                        color = TerminalStandard.Text
                    )
                },
                navigationIcon = {
                    // Ghost/Profile icon - only show if identity is available
                    if (userIdentity != null) {
                        TextButton(
                            onClick = {
                                showIdentityDialog = true
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = TerminalStandard.Text
                            )
                        ) {
                            Text(
                                text = TerminalStandard.bracketLabel("me"),
                                style = TerminalStandard.Body,
                                color = TerminalStandard.Text
                            )
                        }
                    }
                },
                actions = {
                    // New conversation button - text-based
                    TextButton(onClick = onNewConversation) {
                        Text(
                            text = TerminalStandard.bracketLabel("+ NEW"),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Loading indicator when syncing messages
            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = TerminalStandard.Text
                )
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.syncMessages() },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val currentState = state) {
                    is ConversationListState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = TerminalStandard.Text
                        )
                    }

                    is ConversationListState.Empty -> {
                        EmptyConversationsView(
                            onNewConversation = onNewConversation,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is ConversationListState.Success -> {
                        ConversationList(
                            conversations = currentState.conversations,
                            onConversationClick = onConversationClick,
                            onConversationDelete = { conversationId ->
                                viewModel.deleteConversation(conversationId)
                            }
                        )
                    }

                    is ConversationListState.Error -> {
                        ErrorView(
                            message = currentState.message,
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                }
            }
        }
    }

    // Identity dialog
    if (showIdentityDialog && userIdentity != null) {
        IdentityDialog(
            identity = userIdentity!!,
            onDismiss = { showIdentityDialog = false }
        )
    }
}

/**
 * List of conversations.
 */
@Composable
private fun ConversationList(
    conversations: List<com.void.block.messaging.domain.Conversation>,
    onConversationClick: (String) -> Unit,
    onConversationDelete: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = conversations,
            key = { it.id }
        ) { conversation ->
            ConversationItem(
                conversation = conversation,
                contactName = conversation.contactId, // TODO: Resolve contact name from contacts block via EventBus
                onClick = { onConversationClick(conversation.id) }
            )

            Divider()
        }
    }
}

/**
 * Empty state view.
 */
@Composable
private fun EmptyConversationsView(
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "no conversations",
            style = TerminalStandard.Body,
            color = TerminalStandard.TextSecondary,
            textAlign = TextAlign.Center
        )

        TextButton(
            onClick = onNewConversation,
            colors = ButtonDefaults.textButtonColors(
                containerColor = TerminalStandard.Text,
                contentColor = TerminalStandard.Background
            )
        ) {
            Text(
                text = TerminalStandard.bracketLabel("+ NEW"),
                style = TerminalStandard.Button
            )
        }
    }
}

/**
 * Error view.
 */
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "error",
            style = TerminalStandard.Body,
            color = TerminalStandard.Text,
            textAlign = TextAlign.Center
        )

        Text(
            text = message,
            style = TerminalStandard.Body,
            color = TerminalStandard.TextSecondary,
            textAlign = TextAlign.Center
        )

        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(
                containerColor = TerminalStandard.Text,
                contentColor = TerminalStandard.Background
            )
        ) {
            Text(
                text = TerminalStandard.bracketLabel("RETRY"),
                style = TerminalStandard.Button
            )
        }
    }
}
