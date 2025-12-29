package com.void.block.messaging.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.void.block.messaging.ui.components.MessageBubble
import com.void.block.messaging.ui.components.MessageInputBar
import com.void.block.messaging.ui.components.TypingIndicator
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Chat screen - shows conversation with a specific contact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    contactId: String,
    contactName: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(parameters = { parametersOf(conversationId, contactId) })
) {
    val state by viewModel.state.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Typing indicator (would be shown when contact is typing)
                // For now, just a placeholder
                // if (contactIsTyping) {
                //     TypingIndicator(contactName = contactName)
                // }

                MessageInputBar(
                    message = messageText,
                    onMessageChange = { viewModel.onMessageTextChange(it) },
                    onSend = {
                        viewModel.sendMessage()
                        // Scroll to bottom after sending
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    enabled = state is ChatState.Success
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is ChatState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is ChatState.Success -> {
                    if (currentState.messages.isEmpty()) {
                        EmptyMessagesView(
                            contactName = contactName,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        // Messages list (reversed so newest at bottom)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true, // Newest messages at bottom
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(
                                items = currentState.messages.reversed(),
                                key = { it.id }
                            ) { message ->
                                MessageBubble(message = message)
                            }
                        }
                    }
                }

                is ChatState.Error -> {
                    ErrorView(
                        message = currentState.message,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(state) {
        if (state is ChatState.Success) {
            val messages = (state as ChatState.Success).messages
            if (messages.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
    }
}

/**
 * Empty messages view - shown when conversation has no messages yet.
 */
@Composable
private fun EmptyMessagesView(
    contactName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "No messages yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Say hi to $contactName!",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * Error view.
 */
@Composable
private fun ErrorView(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Error loading messages",
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
    }
}
