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

        // Data layer
        single { MessageRepository(get()) }

        // ViewModels
        viewModel { ConversationListViewModel(get()) }
        viewModel { (conversationId: String, contactId: String) ->
            ChatViewModel(conversationId, contactId, get())
        }
    }

    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        composable(Routes.MESSAGES_LIST) {
            ConversationListScreen(
                onConversationClick = { conversationId ->
                    navigator.navigate("${Routes.MESSAGES_CHAT}/$conversationId")
                },
                onNewConversation = {
                    // TODO: Navigate to contacts to select recipient
                    navigator.navigate(Routes.CONTACTS_LIST)
                }
            )
        }

        composable("${Routes.MESSAGES_CHAT}/{conversationId}") { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            // TODO: Get contactId and contactName from conversation or contacts block
            // For now, using placeholder values
            val contactId = "placeholder_contact"
            val contactName = "Contact"

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
    single { MessageEncryption(get()) }
    single { MessageRepository(get()) }
}
