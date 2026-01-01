package com.void.block.contacts

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.void.block.contacts.data.ContactRepository
import com.void.block.contacts.events.ContactEvent
import com.void.slate.block.Block
import com.void.slate.block.BlockEvents
import com.void.slate.block.BlockManifest
import com.void.slate.navigation.Navigator
import com.void.slate.navigation.Route
import com.void.slate.navigation.Routes
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Contacts Block
 *
 * Contact management:
 * - Contact list
 * - Add via QR code
 * - Add via 3-word ID
 * - Block/unblock
 * - Key verification
 *
 * Architecture:
 * - Domain: Contact, ContactRequest, ThreeWordIdentity
 * - Data: ContactRepository (uses SecureStorage)
 * - Events: ContactAdded, ContactBlocked, ContactVerified, etc.
 */
@Block(id = "contacts", enabledByDefault = true)
class ContactsBlock : BlockManifest {

    override val id: String = "contacts"

    override val routes: List<Route> = listOf(
        Route.Screen(Routes.CONTACTS_LIST, "Contacts"),
        Route.Screen(Routes.CONTACTS_ADD, "Add Contact"),
        Route.Screen(Routes.CONTACTS_SCAN, "Scan QR Code"),
    )

    override val events = BlockEvents(
        emits = listOf(
            ContactEvent.ContactAdded::class,
            ContactEvent.ContactUpdated::class,
            ContactEvent.ContactDeleted::class,
            ContactEvent.ContactBlocked::class,
            ContactEvent.ContactUnblocked::class,
            ContactEvent.ContactVerified::class,
            ContactEvent.ContactRequestReceived::class,
            ContactEvent.ContactRequestAccepted::class,
            ContactEvent.ContactRequestRejected::class,
        ),
        observes = emptyList()
    )

    override fun Module.install() {
        // Data layer
        single { ContactRepository(get(), get()) }  // Added NetworkClient dependency

        // ViewModels
        factory { com.void.block.contacts.ui.viewmodels.ContactsViewModel(get(), get()) }
        factory { com.void.block.contacts.ui.viewmodels.AddContactViewModel(get(), get(), get()) }
    }

    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        // Contacts list screen
        composable(Routes.CONTACTS_LIST) {
            com.void.block.contacts.ui.screens.ContactsListScreen(
                onNavigateToAddContact = { navigator.navigate(Routes.CONTACTS_ADD) },
                onNavigateToScanQR = { navigator.navigate(Routes.CONTACTS_SCAN) },
                onNavigateToContactDetail = { contactId ->
                    // Navigate to chat with this contact
                    navigator.navigate("messages/chat/$contactId")
                }
            )
        }

        // Add contact screen
        composable(Routes.CONTACTS_ADD) {
            com.void.block.contacts.ui.screens.AddContactScreen(
                onNavigateBack = { navigator.goBack() },
                onNavigateToScanQR = { navigator.navigate(Routes.CONTACTS_SCAN) },
                onContactAdded = { contactId ->
                    // Go back to contacts list after adding
                    navigator.goBack()
                }
            )
        }

        // Scan QR screen - placeholder for now
        composable(Routes.CONTACTS_SCAN) {
            // TODO: Implement ScanQRScreen in Phase 2 continuation
            // For now, show simple placeholder
            androidx.compose.material3.Text("QR Scanner - Coming Soon")
        }
    }
}

/**
 * Koin module for this block.
 * Can be used for manual registration if not using auto-discovery.
 */
val contactsModule = module {
    single { ContactRepository(get(), get()) }
    factory { com.void.block.contacts.ui.viewmodels.ContactsViewModel(get(), get()) }
    factory { com.void.block.contacts.ui.viewmodels.AddContactViewModel(get(), get(), get()) }
}
