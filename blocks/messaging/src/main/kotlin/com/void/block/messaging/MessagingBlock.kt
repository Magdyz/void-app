package com.void.block.messaging

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.void.block.messaging.crypto.MessageEncryption
import com.void.block.messaging.data.MessageRepository
import com.void.block.messaging.events.MessageEvent
import com.void.block.messaging.ui.ChatScreen
import com.void.block.messaging.ui.ChatViewModel
import com.void.block.messaging.ui.ConversationListScreen
import com.void.block.messaging.ui.ConversationListViewModel
import com.void.slate.block.Block
import com.void.slate.block.BlockEvents
import com.void.slate.block.BlockManifest
import com.void.slate.navigation.Navigator
import com.void.slate.navigation.Route
import com.void.slate.navigation.Routes
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Messaging Block
 *
 * Core messaging functionality:
 * - End-to-end encrypted messaging using Signal Protocol
 * - Message list and chat views
 * - Message persistence with SecureStorage
 * - Message expiry (disappearing messages)
 * - Read receipts and delivery status
 * - Drafts
 *
 * Architecture:
 * - Domain: Message, Conversation, MessageContent
 * - Crypto: MessageEncryption (simplified Signal Protocol)
 * - Data: MessageRepository (uses SecureStorage)
 * - Events: MessageSent, MessageReceived, MessageRead, etc.
 *
 * Block Isolation:
 * - Does NOT import contacts block
 * - Uses EventBus to request contact information
 * - All crypto through CryptoProvider interface
 * - All storage through SecureStorage interface
 */
@Block(id = "messaging", enabledByDefault = true)
class MessagingBlock : BlockManifest {

    override val id: String = "messaging"

    override val routes: List<Route> = listOf(
        Route.Screen(Routes.MESSAGES_LIST, "Messages"),
        Route.Screen(Routes.MESSAGES_CHAT, "Chat"),
    )

    override val events = BlockEvents(
        emits = listOf(
            MessageEvent.MessageSent::class,
            MessageEvent.MessageReceived::class,
            MessageEvent.MessageDelivered::class,
            MessageEvent.MessageRead::class,
            MessageEvent.MessageFailed::class,
            MessageEvent.MessageExpired::class,
            MessageEvent.MessageDeleted::class,
            MessageEvent.ConversationCreated::class,
            MessageEvent.ConversationDeleted::class,
            MessageEvent.ConversationRead::class,
            MessageEvent.TypingIndicator::class,
        ),
        observes = listOf(
            // We'll observe ContactAdded to create initial conversations
            // But we don't import contacts block - use EventBus only
        )
    )

    override fun Module.install() {
        // Crypto layer
        single { MessageEncryption(get()) }
        // MessageEncryptionService is provided by app module

        // Data layer
        single {
            MessageRepository(
                storage = get(),
                messageSender = get(),
                messageFetcher = get(),
                mailboxDerivation = get(),
                encryptionService = get(),
                publicKeyToContactId = getOrNull()  // Optional callback provided by app module
            )
        }

        // ViewModels
        viewModel { ConversationListViewModel(get(), get()) }
        viewModel { params ->
            ChatViewModel(
                conversationId = params[0],
                contactId = params[1],
                messageRepository = get()
            )
        }
    }

    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        composable(Routes.MESSAGES_LIST) {
            ConversationListScreen(
                onConversationClick = { conversationId ->
                    // When clicking existing conversation, we need to get contactId from conversation
                    // For now, use conversationId as contactId (they should match for 1:1 chats)
                    navigator.navigate("messages/chat/$conversationId")
                },
                onNewConversation = {
                    // Navigate to contacts to select recipient
                    navigator.navigate(Routes.CONTACTS_LIST)
                }
            )
        }

        composable("messages/chat/{contactId}") { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
            // Use contactId as conversationId for now (1:1 chat)
            val conversationId = contactId
            // TODO: Get contactName from contacts block via EventBus
            val contactName = contactId // Use contactId as display name for now

            ChatScreen(
                conversationId = conversationId,
                contactId = contactId,
                contactName = contactName,
                onNavigateBack = { navigator.goBack() }
            )
        }
    }
}

/**
 * Koin module for this block.
 * Can be used for manual registration if not using auto-discovery.
 */
val messagingModule = module {
    // Crypto layer
    single { MessageEncryption(get()) }
    // MessageEncryptionService is provided by app module

    // Data layer
    single {
        MessageRepository(
            storage = get(),
            messageSender = get(),
            messageFetcher = get(),
            mailboxDerivation = get(),
            encryptionService = get(),
            publicKeyToContactId = getOrNull()  // Optional callback provided by app module
        )
    }

    // ViewModels
    viewModel { ConversationListViewModel(get(), get()) }
    viewModel { params ->
        ChatViewModel(
            conversationId = params[0],
            contactId = params[1],
            messageRepository = get()
        )
    }
}
