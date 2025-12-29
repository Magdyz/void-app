package com.void.block.contacts.data

import com.void.block.contacts.domain.Contact
import com.void.block.contacts.domain.ContactRequest
import com.void.block.contacts.domain.ThreeWordIdentity
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Repository for managing contacts.
 * Stores contacts in encrypted storage.
 */
class ContactRepository(
    private val storage: SecureStorage
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _contactRequests = MutableStateFlow<List<ContactRequest>>(emptyList())
    val contactRequests: StateFlow<List<ContactRequest>> = _contactRequests.asStateFlow()

    companion object {
        private const val KEY_PREFIX_CONTACT = "contact."
        private const val KEY_PREFIX_REQUEST = "contact_request."
        private const val KEY_CONTACT_IDS = "contact.all_ids"
        private const val KEY_REQUEST_IDS = "contact_request.all_ids"
    }

    /**
     * Load all contacts from storage.
     */
    suspend fun loadContacts() {
        val ids = getStoredContactIds()
        val loadedContacts = ids.mapNotNull { id ->
            getContact(id)
        }
        _contacts.value = loadedContacts
    }

    /**
     * Load all contact requests from storage.
     */
    suspend fun loadContactRequests() {
        val ids = getStoredRequestIds()
        val loadedRequests = ids.mapNotNull { id ->
            getContactRequest(id)
        }
        _contactRequests.value = loadedRequests
    }

    /**
     * Add a new contact.
     */
    suspend fun addContact(contact: Contact) {
        // Store contact
        val key = "$KEY_PREFIX_CONTACT${contact.id}"
        val contactJson = json.encodeToString(contact)
        storage.put(key, contactJson.toByteArray())

        // Update IDs list
        val ids = getStoredContactIds().toMutableSet()
        ids.add(contact.id)
        saveContactIds(ids)

        // Update in-memory list
        _contacts.value = _contacts.value + contact
    }

    /**
     * Update an existing contact.
     */
    suspend fun updateContact(contact: Contact) {
        val key = "$KEY_PREFIX_CONTACT${contact.id}"
        val contactJson = json.encodeToString(contact)
        storage.put(key, contactJson.toByteArray())

        // Update in-memory list
        _contacts.value = _contacts.value.map {
            if (it.id == contact.id) contact else it
        }
    }

    /**
     * Delete a contact.
     */
    suspend fun deleteContact(contactId: String) {
        // Remove from storage
        val key = "$KEY_PREFIX_CONTACT$contactId"
        storage.delete(key)

        // Update IDs list
        val ids = getStoredContactIds().toMutableSet()
        ids.remove(contactId)
        saveContactIds(ids)

        // Update in-memory list
        _contacts.value = _contacts.value.filter { it.id != contactId }
    }

    /**
     * Get contact by ID.
     */
    suspend fun getContact(contactId: String): Contact? {
        val key = "$KEY_PREFIX_CONTACT$contactId"
        val bytes = storage.get(key) ?: return null
        return try {
            json.decodeFromString(bytes.decodeToString())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find contact by identity.
     */
    suspend fun findContactByIdentity(identity: ThreeWordIdentity): Contact? {
        return _contacts.value.find { it.identity == identity }
    }

    /**
     * Find contact by public key.
     */
    suspend fun findContactByPublicKey(publicKey: ByteArray): Contact? {
        return _contacts.value.find { it.publicKey.contentEquals(publicKey) }
    }

    /**
     * Block/unblock a contact.
     */
    suspend fun setContactBlocked(contactId: String, blocked: Boolean) {
        val contact = getContact(contactId) ?: return
        updateContact(contact.copy(blocked = blocked))
    }

    /**
     * Mark contact as verified (after key verification).
     */
    suspend fun setContactVerified(contactId: String, verified: Boolean) {
        val contact = getContact(contactId) ?: return
        updateContact(contact.copy(verified = verified))
    }

    /**
     * Update contact's last seen timestamp.
     */
    suspend fun updateLastSeen(contactId: String) {
        val contact = getContact(contactId) ?: return
        updateContact(contact.copy(lastSeenAt = System.currentTimeMillis()))
    }

    /**
     * Set contact nickname.
     */
    suspend fun setContactNickname(contactId: String, nickname: String?) {
        val contact = getContact(contactId) ?: return
        updateContact(contact.copy(displayName = nickname))
    }

    // ═══════════════════════════════════════════════════════════════
    // Contact Requests
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add a contact request.
     */
    suspend fun addContactRequest(request: ContactRequest) {
        val key = "$KEY_PREFIX_REQUEST${request.id}"
        val requestJson = json.encodeToString(request)
        storage.put(key, requestJson.toByteArray())

        // Update IDs list
        val ids = getStoredRequestIds().toMutableSet()
        ids.add(request.id)
        saveRequestIds(ids)

        // Update in-memory list
        _contactRequests.value = _contactRequests.value + request
    }

    /**
     * Update contact request status.
     */
    suspend fun updateContactRequest(request: ContactRequest) {
        val key = "$KEY_PREFIX_REQUEST${request.id}"
        val requestJson = json.encodeToString(request)
        storage.put(key, requestJson.toByteArray())

        // Update in-memory list
        _contactRequests.value = _contactRequests.value.map {
            if (it.id == request.id) request else it
        }
    }

    /**
     * Delete a contact request.
     */
    suspend fun deleteContactRequest(requestId: String) {
        val key = "$KEY_PREFIX_REQUEST$requestId"
        storage.delete(key)

        // Update IDs list
        val ids = getStoredRequestIds().toMutableSet()
        ids.remove(requestId)
        saveRequestIds(ids)

        // Update in-memory list
        _contactRequests.value = _contactRequests.value.filter { it.id != requestId }
    }

    /**
     * Get contact request by ID.
     */
    suspend fun getContactRequest(requestId: String): ContactRequest? {
        val key = "$KEY_PREFIX_REQUEST$requestId"
        val bytes = storage.get(key) ?: return null
        return try {
            json.decodeFromString(bytes.decodeToString())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Accept a contact request and create contact.
     */
    suspend fun acceptContactRequest(requestId: String): Contact? {
        val request = getContactRequest(requestId) ?: return null

        // Create contact from request
        val contact = Contact(
            id = UUID.randomUUID().toString(),
            identity = request.fromIdentity,
            displayName = null,
            publicKey = request.publicKey,
            identityKey = request.identityKey,
            verified = false,
            blocked = false,
            fingerprint = ""
        ).let {
            it.copy(fingerprint = it.generateFingerprint())
        }

        // Add contact
        addContact(contact)

        // Update request status
        updateContactRequest(request.copy(status = ContactRequest.RequestStatus.ACCEPTED))

        // Delete request after acceptance
        deleteContactRequest(requestId)

        return contact
    }

    /**
     * Reject a contact request.
     */
    suspend fun rejectContactRequest(requestId: String) {
        val request = getContactRequest(requestId) ?: return
        updateContactRequest(request.copy(status = ContactRequest.RequestStatus.REJECTED))
        deleteContactRequest(requestId)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

    private suspend fun getStoredContactIds(): Set<String> {
        val bytes = storage.get(KEY_CONTACT_IDS) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(bytes.decodeToString())
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun saveContactIds(ids: Set<String>) {
        val idsJson = json.encodeToString(ids)
        storage.put(KEY_CONTACT_IDS, idsJson.toByteArray())
    }

    private suspend fun getStoredRequestIds(): Set<String> {
        val bytes = storage.get(KEY_REQUEST_IDS) ?: return emptySet()
        return try {
            json.decodeFromString<Set<String>>(bytes.decodeToString())
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun saveRequestIds(ids: Set<String>) {
        val idsJson = json.encodeToString(ids)
        storage.put(KEY_REQUEST_IDS, idsJson.toByteArray())
    }

    /**
     * Clear all contacts (for panic wipe or reset).
     */
    suspend fun clearAll() {
        // Delete all contact data
        val contactIds = getStoredContactIds()
        contactIds.forEach { id ->
            storage.delete("$KEY_PREFIX_CONTACT$id")
        }
        storage.delete(KEY_CONTACT_IDS)

        // Delete all request data
        val requestIds = getStoredRequestIds()
        requestIds.forEach { id ->
            storage.delete("$KEY_PREFIX_REQUEST$id")
        }
        storage.delete(KEY_REQUEST_IDS)

        // Clear in-memory lists
        _contacts.value = emptyList()
        _contactRequests.value = emptyList()
    }
}
