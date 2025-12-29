package com.void.block.messaging.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.messaging.ui.components.ConversationItem
import org.koin.androidx.compose.koinViewModel

/**
 * Conversation list screen - shows all active conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationClick: (String) -> Unit,
    onNewConversation: () -> Unit,
    viewModel: ConversationListViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                actions = {
                    IconButton(onClick = onNewConversation) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New conversation"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is ConversationListState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
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
            text = "No conversations yet",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Start a new conversation by adding a contact",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Button(onClick = onNewConversation) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("New Conversation")
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
            text = "Error loading conversations",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
