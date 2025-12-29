package com.void.block.contacts.events

import com.void.slate.event.Event

/**
 * Events emitted by the Contacts block.
 */
sealed class ContactEvent : Event {

    /**
     * Emitted when a new contact is added.
     */
    data class ContactAdded(
        val contactId: String,
        val identity: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a contact is updated.
     */
    data class ContactUpdated(
        val contactId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a contact is deleted.
     */
    data class ContactDeleted(
        val contactId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a contact is blocked.
     */
    data class ContactBlocked(
        val contactId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a contact is unblocked.
     */
    data class ContactUnblocked(
        val contactId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a contact is verified (key verification complete).
     */
    data class ContactVerified(
        val contactId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a new contact request is received.
     */
    data class ContactRequestReceived(
        val requestId: String,
        val fromIdentity: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a contact request is accepted.
     */
    data class ContactRequestAccepted(
        val requestId: String,
        val contactId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()

    /**
     * Emitted when a contact request is rejected.
     */
    data class ContactRequestRejected(
        val requestId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ContactEvent()
}
