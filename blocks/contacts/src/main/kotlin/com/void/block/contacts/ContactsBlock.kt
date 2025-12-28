package com.void.block.contacts

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
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
        emits = listOf(ContactAdded::class, ContactBlocked::class),
        observes = listOf()
    )
    
    override fun Module.install() {
        // TODO: Register dependencies
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        // TODO: Set up navigation
    }
}

// Events
data class ContactAdded(val contactId: String, override val timestamp: Long = System.currentTimeMillis()) : com.void.slate.event.Event
data class ContactBlocked(val contactId: String, override val timestamp: Long = System.currentTimeMillis()) : com.void.slate.event.Event

val contactsModule = module {
    // Contacts block dependencies
}
