package com.void.block.contacts.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.contacts.data.ContactRepository
import com.void.block.contacts.domain.Contact
import com.void.block.contacts.domain.ThreeWordIdentity
import com.void.block.contacts.events.ContactEvent
import com.void.slate.event.EventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for adding a new contact.
 */
class AddContactViewModel(
    private val repository: ContactRepository,
    private val eventBus: EventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow<AddContactUiState>(AddContactUiState.Input)
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    private val _identityInput = MutableStateFlow("")
    val identityInput: StateFlow<String> = _identityInput.asStateFlow()

    private val _nicknameInput = MutableStateFlow("")
    val nicknameInput: StateFlow<String> = _nicknameInput.asStateFlow()

    /**
     * Update the identity input field.
     */
    fun onIdentityChanged(value: String) {
        _identityInput.value = value.lowercase()
    }

    /**
     * Update the nickname input field.
     */
    fun onNicknameChanged(value: String) {
        _nicknameInput.value = value
    }

    /**
     * Validate and add the contact.
     */
    fun addContact() {
        val identityStr = _identityInput.value.trim()
        val nickname = _nicknameInput.value.trim().takeIf { it.isNotEmpty() }

        // Validate identity format
        val identity = ThreeWordIdentity.parse(identityStr)
        if (identity == null) {
            _uiState.value = AddContactUiState.Error("Invalid identity format. Use: word1.word2.word3")
            return
        }

        // Check if contact already exists
        viewModelScope.launch {
            val existing = repository.findContactByIdentity(identity)
            if (existing != null) {
                _uiState.value = AddContactUiState.Error("Contact already exists")
                return@launch
            }

            // Create new contact
            // TODO: In real implementation, we'd need to exchange keys first
            // For now, create placeholder contact
            val contact = Contact(
                id = UUID.randomUUID().toString(),
                identity = identity,
                displayName = nickname,
                publicKey = ByteArray(32),  // TODO: Exchange via QR or network
                identityKey = ByteArray(32),  // TODO: Exchange via QR or network
                verified = false,
                blocked = false,
                fingerprint = ""
            ).let {
                it.copy(fingerprint = it.generateFingerprint())
            }

            try {
                repository.addContact(contact)
                eventBus.emit(ContactEvent.ContactAdded(contact.id, contact.identity.toString()))
                _uiState.value = AddContactUiState.Success(contact.id)
            } catch (e: Exception) {
                _uiState.value = AddContactUiState.Error("Failed to add contact: ${e.message}")
            }
        }
    }

    /**
     * Reset the UI state.
     */
    fun resetState() {
        _uiState.value = AddContactUiState.Input
        _identityInput.value = ""
        _nicknameInput.value = ""
    }
}

/**
 * UI state for adding a contact.
 */
sealed class AddContactUiState {
    data object Input : AddContactUiState()
    data class Error(val message: String) : AddContactUiState()
    data class Success(val contactId: String) : AddContactUiState()
}
