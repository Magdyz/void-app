package com.void.block.contacts.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.contacts.data.ContactRepository
import com.void.block.contacts.domain.Contact
import com.void.block.contacts.domain.ContactRequest
import com.void.block.contacts.events.ContactEvent
import com.void.slate.event.EventBus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the contacts list screen.
 */
class ContactsViewModel(
    private val repository: ContactRepository,
    private val eventBus: EventBus
) : ViewModel() {

    /**
     * List of all contacts.
     */
    val contacts: StateFlow<List<Contact>> = repository.contacts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Pending contact requests.
     */
    val contactRequests: StateFlow<List<ContactRequest>> = repository.contactRequests
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Load contacts when ViewModel is created
        viewModelScope.launch {
            repository.loadContacts()
            repository.loadContactRequests()
        }
    }

    /**
     * Delete a contact.
     */
    fun deleteContact(contactId: String) {
        viewModelScope.launch {
            repository.deleteContact(contactId)
            eventBus.emit(ContactEvent.ContactDeleted(contactId))
        }
    }

    /**
     * Block a contact.
     */
    fun blockContact(contactId: String) {
        viewModelScope.launch {
            repository.setContactBlocked(contactId, blocked = true)
            eventBus.emit(ContactEvent.ContactBlocked(contactId))
        }
    }

    /**
     * Unblock a contact.
     */
    fun unblockContact(contactId: String) {
        viewModelScope.launch {
            repository.setContactBlocked(contactId, blocked = false)
            eventBus.emit(ContactEvent.ContactUnblocked(contactId))
        }
    }

    /**
     * Accept a contact request.
     */
    fun acceptContactRequest(requestId: String) {
        viewModelScope.launch {
            val contact = repository.acceptContactRequest(requestId)
            if (contact != null) {
                eventBus.emit(ContactEvent.ContactRequestAccepted(requestId, contact.id))
                eventBus.emit(ContactEvent.ContactAdded(contact.id, contact.identity.toString()))
            }
        }
    }

    /**
     * Reject a contact request.
     */
    fun rejectContactRequest(requestId: String) {
        viewModelScope.launch {
            repository.rejectContactRequest(requestId)
            eventBus.emit(ContactEvent.ContactRequestRejected(requestId))
        }
    }

    /**
     * Refresh contacts and requests from storage.
     */
    fun refresh() {
        viewModelScope.launch {
            repository.loadContacts()
            repository.loadContactRequests()

            // Also poll for new contact requests from network
            repository.pollContactRequests()
        }
    }
}
