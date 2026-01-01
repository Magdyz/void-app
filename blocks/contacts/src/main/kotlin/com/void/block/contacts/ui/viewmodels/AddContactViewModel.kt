package com.void.block.contacts.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.contacts.data.ContactRepository
import com.void.block.contacts.domain.Contact
import com.void.block.contacts.domain.ThreeWordIdentity
import com.void.block.contacts.events.ContactEvent
import com.void.slate.crypto.CryptoProvider
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
    private val eventBus: EventBus,
    private val crypto: CryptoProvider
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

            // Generate deterministic seed from identity
            val identitySeed = generateDeterministicSeed(identity)

            // Derive encryption keys from seed (deterministic)
            // This allows manual contact exchange by just sharing the three words
            val (publicKey, identityKey) = deriveKeysFromSeed(identitySeed)

            // Create new contact
            val contact = Contact(
                id = UUID.randomUUID().toString(),
                identity = identity,
                displayName = nickname,
                publicKey = publicKey,      // ✓ Derived from seed!
                identityKey = identityKey,  // ✓ Derived from seed!
                identitySeed = identitySeed,
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

    /**
     * Generate deterministic seed from three-word identity.
     * Uses the same derivation as identity generation, so the same words
     * always produce the same seed. This enables manual contact exchange.
     */
    private fun generateDeterministicSeed(identity: ThreeWordIdentity): ByteArray {
        // Match the derivation in GenerateIdentity.deriveSeedFromWords()
        val combined = "${identity.word1}.${identity.word2}.${identity.word3}"

        // Use HKDF-like derivation for consistency with crypto.derive()
        // This matches: crypto.derive(ByteArray(32), combined)
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(combined.toByteArray())
    }

    /**
     * Derive encryption keys from identity seed (deterministic).
     *
     * IMPORTANT: For Phase 2, we derive keys deterministically from the seed.
     * This allows manual contact exchange (just share the 3 words).
     * In Phase 3, this will be replaced with proper key exchange via QR/network.
     *
     * @return Pair of (publicEncryptionKey, publicIdentityKey)
     */
    private suspend fun deriveKeysFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        // Derive encryption key pair (X25519 for ECDH)
        // Use the seed to generate a deterministic key pair
        val encryptionKeyPair = crypto.deriveKeyPairFromSeed(seed, "encryption")

        // Derive identity key pair (Ed25519 for signatures)
        val identityKeyPair = crypto.deriveKeyPairFromSeed(seed, "identity")

        return Pair(
            encryptionKeyPair.publicKey,
            identityKeyPair.publicKey
        )
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
