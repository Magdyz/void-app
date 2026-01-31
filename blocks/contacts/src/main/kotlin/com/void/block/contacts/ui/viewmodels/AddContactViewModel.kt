package com.void.block.contacts.ui.viewmodels

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.contacts.data.ContactRepository
import com.void.block.contacts.domain.Contact
import com.void.block.contacts.domain.ContactQRData
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
     * 🚨 DEPRECATED: This method derives keys from three-word identity, which is INSECURE!
     *
     * Phase 3 Security Issue:
     * - Three-word identity should NOT derive cryptographic keys
     * - Anyone who knows the three words can derive the person's PRIVATE KEYS
     * - This is a catastrophic security vulnerability
     *
     * Use `addContactFromQR()` instead, which receives keys via secure QR code exchange.
     */
    @Deprecated(
        message = "Insecure! Derives keys from three-word identity. Use addContactFromQR() instead.",
        level = DeprecationLevel.ERROR
    )
    fun addContact() {
        _uiState.value = AddContactUiState.Error(
            "Manual contact addition is deprecated for security reasons. " +
            "Please use QR code scanning to add contacts securely."
        )
    }

    /**
     * Add a contact from QR code data (Phase 3 secure method).
     *
     * 🔒 SECURITY: QR code contains actual public keys and mailboxSeed (not derived from three words).
     * - publicKey: Their X25519 public key (for encrypting messages TO them)
     * - identityKey: Their Ed25519 public key (for verifying their signatures)
     * - mailboxSeed: Their mailbox seed (for deriving their mailbox addresses)
     *
     * mailboxSeed is SAFE TO SHARE - it cannot derive private keys.
     *
     * @param qrData The contact data scanned from QR code
     */
    fun addContactFromQR(qrData: ContactQRData) {
        viewModelScope.launch {
            try {
                android.util.Log.d("VOID_QR", "🔍 [QR_PARSE] Received QR data:")
                android.util.Log.d("VOID_QR", "  Identity: ${qrData.identity}")
                android.util.Log.d("VOID_QR", "  PublicKey (Base64): ${qrData.publicKey.take(20)}...")
                android.util.Log.d("VOID_QR", "  IdentityKey (Base64): ${qrData.identityKey.take(20)}...")
                android.util.Log.d("VOID_QR", "  MailboxSeed (Base64): ${qrData.mailboxSeed.take(20)}...")
                android.util.Log.d("VOID_QR", "  Timestamp: ${qrData.timestamp}")

                // Validate QR code timestamp (reject if older than 5 minutes)
                val now = System.currentTimeMillis()
                val ageMinutes = (now - qrData.timestamp) / (1000 * 60)
                if (ageMinutes > 5) {
                    _uiState.value = AddContactUiState.Error(
                        "QR code expired (${ageMinutes} minutes old). " +
                        "Ask them to generate a new QR code."
                    )
                    return@launch
                }

                // Check if contact already exists
                val existing = repository.findContactByIdentity(qrData.identity)
                if (existing != null) {
                    _uiState.value = AddContactUiState.Error("Contact already exists")
                    return@launch
                }

                // Decode Base64 keys and seed
                val publicKey = Base64.decode(qrData.publicKey, Base64.NO_WRAP)
                val identityKey = Base64.decode(qrData.identityKey, Base64.NO_WRAP)
                val mailboxSeed = Base64.decode(qrData.mailboxSeed, Base64.NO_WRAP)

                android.util.Log.d("VOID_QR", "✅ [QR_DECODE] Decoded binary data:")
                android.util.Log.d("VOID_QR", "  PublicKey: ${publicKey.size} bytes")
                android.util.Log.d("VOID_QR", "  IdentityKey: ${identityKey.size} bytes")
                android.util.Log.d("VOID_QR", "  MailboxSeed: ${mailboxSeed.size} bytes (first 16: ${mailboxSeed.take(16).joinToString("") { "%02x".format(it) }})")

                // Create new contact
                val contact = Contact(
                    id = UUID.randomUUID().toString(),
                    identity = qrData.identity,
                    displayName = _nicknameInput.value.trim().takeIf { it.isNotEmpty() },
                    publicKey = publicKey,
                    identityKey = identityKey,
                    mailboxSeed = mailboxSeed,  // 🆕 Phase 3: SAFE TO SHARE
                    verified = true,  // Verified via QR code exchange in person
                    blocked = false,
                    fingerprint = ""
                ).let {
                    it.copy(fingerprint = it.generateFingerprint())
                }

                android.util.Log.d("VOID_QR", "💾 [QR_SAVE] Saving contact:")
                android.util.Log.d("VOID_QR", "  ContactID: ${contact.id}")
                android.util.Log.d("VOID_QR", "  Identity: ${contact.identity}")
                android.util.Log.d("VOID_QR", "  MailboxSeed stored: ${contact.mailboxSeed.take(16).joinToString("") { "%02x".format(it) }}")

                repository.addContact(contact)
                eventBus.emit(ContactEvent.ContactAdded(contact.id, contact.identity.toString()))
                _uiState.value = AddContactUiState.Success(contact.id)

                android.util.Log.d("VOID_QR", "✅ [QR_COMPLETE] Contact added successfully")
            } catch (e: Exception) {
                android.util.Log.e("VOID_QR", "❌ [QR_ERROR] Failed to add contact", e)
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

    // 🗑️ REMOVED: generateDeterministicSeed() - SECURITY VULNERABILITY
    // Three-word identity should NEVER derive cryptographic keys!
    // Anyone with the three words could derive private keys.

    // 🗑️ REMOVED: deriveKeysFromSeed() - SECURITY VULNERABILITY
    // Keys must be exchanged via QR code, not derived from identity.
}

/**
 * UI state for adding a contact.
 */
sealed class AddContactUiState {
    data object Input : AddContactUiState()
    data class Error(val message: String) : AddContactUiState()
    data class Success(val contactId: String) : AddContactUiState()
}
