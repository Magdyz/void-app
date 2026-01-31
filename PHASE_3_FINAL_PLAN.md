# Phase 3 Implementation Plan (Final)

**Date:** 2026-01-06
**Status:** Ready for Implementation
**Breaking Changes:** YES - Hard fork, all existing data will be wiped

---

## 🎯 Overview

This plan implements Phase 3 security enhancements with **all critical fixes** included from the start. Since there are no real contacts yet, we do a clean break with the correct architecture.

### What's Included

1. ✅ **Hierarchical Key Derivation** - Separate mailboxSeed from private keys (SECURITY CRITICAL)
2. ✅ **Protocol Version Byte** - 0x02 prefix for efficient version detection
3. ✅ **Double Ratchet with Forward Secrecy** - Standard Signal Protocol
4. ✅ **Skipped Message Handling** - Handle out-of-order delivery (CRITICAL FOR PRODUCTION)
5. ✅ **Proper HMAC Implementation** - Use Tink, not manual (BEST PRACTICE)

---

## 🔐 Part 1: Fix Key Derivation (SECURITY CRITICAL)

### Problem

**Current:** `identitySeed` derives BOTH private keys AND mailbox addresses → Anyone with identitySeed can decrypt all messages!

**Fix:** Separate mailbox derivation from private key derivation using hierarchical HKDF.

### New Architecture

```
identitySeed (32 bytes, NEVER SHARED)
  ├─> HKDF(identitySeed, "encryption") → X25519 privateKey (NEVER SHARED)
  ├─> HKDF(identitySeed, "identity") → Ed25519 privateKey (NEVER SHARED)
  └─> HKDF(identitySeed, "mailbox-seed") → mailboxSeed (32 bytes, CAN BE SHARED)
        └─> HKDF(mailboxSeed, "epoch/N") → mailbox address
```

**Security Guarantee:** `mailboxSeed` CANNOT derive private keys (different HKDF domain)

### Implementation

#### File 1: `IdentityRepository.kt`

**Location:** `blocks/identity/src/main/kotlin/com/void/block/identity/data/IdentityRepository.kt`

**Changes:**

```kotlin
private suspend fun generateAndStoreKeyPairs() {
    val identity = _identity.value ?: getIdentity()
    require(identity != null) { "Identity must exist before generating keys" }

    // Derive encryption key pair (X25519 for ECDH)
    val encryptionKeyPair = crypto.deriveKeyPairFromSeed(identity.seed, "encryption")
    secureStorage.put(KEY_ENCRYPTION_PUBLIC, encryptionKeyPair.publicKey)
    secureStorage.put(KEY_ENCRYPTION_PRIVATE, encryptionKeyPair.privateKey)

    // Derive identity key pair (Ed25519 for signatures)
    val identityKeyPair = crypto.deriveKeyPairFromSeed(identity.seed, "identity")
    secureStorage.put(KEY_IDENTITY_PUBLIC, identityKeyPair.publicKey)
    secureStorage.put(KEY_IDENTITY_PRIVATE, identityKeyPair.privateKey)

    // 🆕 NEW: Derive mailbox seed (SAFE TO SHARE)
    val mailboxSeed = crypto.derive(identity.seed, "mailbox-seed")
    secureStorage.put(KEY_MAILBOX_SEED, mailboxSeed)

    Log.d(TAG, "✓ [KEY_STORE] Generated: encryption, identity, mailbox seeds")
}

// 🆕 NEW: Add getter for mailbox seed
suspend fun getMailboxSeed(): ByteArray? {
    return secureStorage.get(KEY_MAILBOX_SEED)
}

companion object {
    private const val TAG = "VOID_SECURITY"
    private const val KEY_SEED = "identity.seed"
    private const val KEY_NONCE = "identity.nonce"
    private const val KEY_WORDS = "identity.words"
    private const val KEY_CREATED = "identity.created"
    private const val KEY_ENCRYPTION_PUBLIC = "identity.encryption.public"
    private const val KEY_ENCRYPTION_PRIVATE = "identity.encryption.private"
    private const val KEY_IDENTITY_PUBLIC = "identity.identity.public"
    private const val KEY_IDENTITY_PRIVATE = "identity.identity.private"
    private const val KEY_MAILBOX_SEED = "identity.mailbox.seed"  // 🆕 NEW
}
```

#### File 2: `MailboxDerivation.kt`

**Location:** `slate/network/src/main/kotlin/com/void/slate/network/mailbox/MailboxDerivation.kt`

**Changes:** Replace ALL `identitySeed` parameters with `mailboxSeed`

```kotlin
/**
 * Derive the current mailbox address for a given mailbox seed.
 *
 * 🔒 SECURITY: mailboxSeed is SAFE TO SHARE - it cannot derive private keys.
 * It is derived from identitySeed via HKDF with domain "mailbox-seed".
 *
 * @param mailboxSeed The user's 32-byte mailbox seed (NOT identity seed!)
 * @param timestamp Current timestamp in milliseconds (for epoch calculation)
 * @return 64-character hex mailbox hash
 */
suspend fun deriveMailbox(
    mailboxSeed: ByteArray,
    timestamp: Long = System.currentTimeMillis()
): String {
    require(mailboxSeed.size == 32) { "Mailbox seed must be 32 bytes" }

    val epoch = calculateEpoch(timestamp)
    return deriveMailboxForEpoch(mailboxSeed, epoch)
}

suspend fun deriveMailboxForEpoch(
    mailboxSeed: ByteArray,
    epoch: Long
): String {
    require(mailboxSeed.size == 32) { "Mailbox seed must be 32 bytes" }

    // Derive mailbox using KDF: HKDF(mailboxSeed, "epoch/{epoch}")
    val derivationPath = "epoch/$epoch"
    val derived = crypto.derive(mailboxSeed, derivationPath)

    // Hash to 32 bytes for mailbox address
    val mailboxBytes = hashTo16Bytes(derived)
    return mailboxBytes.toHexString()
}

suspend fun getActiveMailboxes(
    mailboxSeed: ByteArray,
    timestamp: Long = System.currentTimeMillis()
): List<MailboxAddress> {
    val currentEpoch = calculateEpoch(timestamp)
    val epochProgress = getEpochProgress(timestamp)

    val mailboxes = mutableListOf<MailboxAddress>()

    if (epochProgress < ROTATION_WINDOW_START_MS) {
        mailboxes.add(
            MailboxAddress(
                hash = deriveMailboxForEpoch(mailboxSeed, currentEpoch - 1),
                epoch = currentEpoch - 1,
                isPrimary = false
            )
        )
    }

    mailboxes.add(
        MailboxAddress(
            hash = deriveMailboxForEpoch(mailboxSeed, currentEpoch),
            epoch = currentEpoch,
            isPrimary = true
        )
    )

    if (epochProgress > ROTATION_WINDOW_END_MS) {
        mailboxes.add(
            MailboxAddress(
                hash = deriveMailboxForEpoch(mailboxSeed, currentEpoch + 1),
                epoch = currentEpoch + 1,
                isPrimary = false
            )
        )
    }

    return mailboxes
}
```

#### File 3: `Contact.kt`

**Location:** `blocks/contacts/src/main/kotlin/com/void/block/contacts/domain/Contact.kt`

**Changes:**

```kotlin
@Serializable
data class Contact(
    val id: String,
    val identity: ThreeWordIdentity,
    val displayName: String?,
    val publicKey: ByteArray,           // X25519 public key
    val identityKey: ByteArray,         // Ed25519 identity key
    val mailboxSeed: ByteArray,         // 🆕 CHANGED: Mailbox seed (SAFE TO SHARE)
    val verified: Boolean = false,
    val blocked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
    val fingerprint: String = "",
) {
    // ... rest unchanged
}

/**
 * QR code data for contact exchange.
 *
 * 🔒 SECURITY: This data is SAFE TO SHARE.
 * - publicKey: Can encrypt messages TO you (cannot decrypt)
 * - identityKey: Can verify YOUR signatures (cannot forge)
 * - mailboxSeed: Can send messages to YOUR mailbox (cannot derive private keys)
 */
@Serializable
data class ContactQRData(
    val identity: ThreeWordIdentity,
    val publicKey: String,              // Base64 X25519 public key
    val identityKey: String,            // Base64 Ed25519 public key
    val mailboxSeed: String,            // 🆕 NEW: Base64 mailbox seed (SAFE!)
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return kotlinx.serialization.json.Json.encodeToString(serializer(), this)
    }

    companion object {
        fun fromJson(json: String): ContactQRData? {
            return try {
                kotlinx.serialization.json.Json.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
        }
    }
}
```

#### File 4: `MessageSender.kt`

**Location:** `slate/network/src/main/kotlin/com/void/slate/network/supabase/MessageSender.kt`

**Changes:**

```kotlin
/**
 * Send an encrypted message to a recipient.
 *
 * @param recipientMailboxSeed The recipient's mailbox seed (NOT identity seed!)
 * @param encryptedPayload The E2E encrypted message payload
 * @param timestamp Current timestamp (for mailbox derivation)
 * @return Result with message ID or error
 */
suspend fun sendMessage(
    recipientMailboxSeed: ByteArray,  // 🆕 CHANGED from recipientSeed
    encryptedPayload: ByteArray,
    timestamp: Long = System.currentTimeMillis()
): Result<String> {
    return try {
        require(recipientMailboxSeed.size == 32) { "Mailbox seed must be 32 bytes" }
        // ... rest unchanged, just use recipientMailboxSeed
        val mailboxHash = mailboxDerivation.deriveMailbox(recipientMailboxSeed, timestamp)
        // ... rest unchanged
    }
}
```

#### File 5: `AddContactViewModel.kt`

**Location:** `blocks/contacts/src/main/kotlin/com/void/block/contacts/ui/viewmodels/AddContactViewModel.kt`

**Changes:**

```kotlin
/**
 * Add contact from QR code data.
 */
fun addContactFromQR(qrData: ContactQRData) {
    viewModelScope.launch {
        try {
            val contact = Contact(
                id = UUID.randomUUID().toString(),
                identity = qrData.identity,
                displayName = null,
                publicKey = qrData.publicKey.fromBase64(),
                identityKey = qrData.identityKey.fromBase64(),
                mailboxSeed = qrData.mailboxSeed.fromBase64(),  // 🆕 Use from QR
                fingerprint = generateFingerprint(qrData.identityKey.fromBase64())
            )

            contactRepository.addContact(contact)
            _state.value = AddContactState.Success
        } catch (e: Exception) {
            _state.value = AddContactState.Error(e.message ?: "Failed to add contact")
        }
    }
}

// 🗑️ DELETE THIS METHOD - No longer deriving keys from three-word identity!
// private suspend fun deriveKeysFromSeed(...) { ... }
```

---

## 🔢 Part 2: Add Protocol Version Byte

### Implementation

#### File: `MessageEncryption.kt`

**Location:** `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/MessageEncryption.kt`

**Changes:**

```kotlin
/**
 * Serialize encrypted message to bytes for transmission.
 * Format: [VERSION_BYTE][JSON_PAYLOAD]
 */
fun serializeEncryptedMessage(encrypted: EncryptedMessage): ByteArray {
    val jsonString = json.encodeToString(EncryptedMessage.serializer(), encrypted)
    val jsonBytes = jsonString.toByteArray()

    // Prepend version byte (0x01 for V1, 0x02 for V2)
    val versionByte = encrypted.version.toByte()
    return byteArrayOf(versionByte) + jsonBytes
}

/**
 * Deserialize encrypted message from bytes.
 * Format: [VERSION_BYTE][JSON_PAYLOAD]
 */
fun deserializeEncryptedMessage(bytes: ByteArray): EncryptedMessage {
    require(bytes.isNotEmpty()) { "Cannot deserialize empty message" }

    // Read version byte
    val versionByte = bytes[0].toInt()
    val jsonBytes = bytes.drop(1).toByteArray()

    return when (versionByte) {
        0x01 -> {
            val jsonString = jsonBytes.decodeToString()
            json.decodeFromString(EncryptedMessage.serializer(), jsonString)
        }
        0x02 -> {
            throw UnsupportedOperationException("V2 not yet implemented")
        }
        else -> {
            // Unknown version - try V1 for backward compatibility
            val jsonString = bytes.decodeToString()
            json.decodeFromString(EncryptedMessage.serializer(), jsonString)
        }
    }
}

companion object {
    private const val PROTOCOL_VERSION = 1

    fun detectVersion(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null

        return when (payload[0].toInt()) {
            0x01 -> 1
            0x02 -> 2
            else -> null
        }
    }
}
```

---

## 🔄 Part 3: Double Ratchet (with CRITICAL Fixes)

### 3A: Add HMAC to CryptoProvider (15 minutes)

#### File: `CryptoProvider.kt`

**Location:** `slate/core/src/main/kotlin/com/void/slate/crypto/CryptoProvider.kt`

**Changes:**

```kotlin
interface CryptoProvider {
    // ... existing methods ...

    /**
     * Compute HMAC-SHA256.
     *
     * @param key HMAC key
     * @param data Data to authenticate
     * @return 32-byte HMAC tag
     */
    suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
}
```

#### File: `TinkCryptoProvider.kt`

**Location:** `slate/crypto/src/main/kotlin/com/void/slate/crypto/impl/TinkCryptoProvider.kt`

**Changes:**

```kotlin
import com.google.crypto.tink.subtle.PrfMac
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TinkCryptoProvider : CryptoProvider {
    // ... existing methods ...

    override suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key, "HmacSHA256")
            mac.init(secretKey)
            mac.doFinal(data)
        } catch (e: Exception) {
            throw RuntimeException("HMAC computation failed", e)
        }
    }
}
```

### 3B: Implement Double Ratchet with Skipped Messages (CRITICAL)

#### File 1: `RatchetSession.kt` (NEW)

**Location:** `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/RatchetSession.kt`

```kotlin
package com.void.block.messaging.crypto

import kotlinx.serialization.Serializable

/**
 * Double Ratchet session state.
 *
 * Rotation behavior:
 * - Symmetric ratchet: Rotates on EVERY message (send/receive)
 * - DH ratchet: Rotates when receiving message with NEW DH public key (ping-pong)
 *
 * NO FORCED ROTATION COUNTER! Let it naturally ratchet with replies.
 */
@Serializable
data class RatchetSession(
    val contactId: String,

    /** Root key for deriving new chain keys (32 bytes) */
    val rootKey: ByteArray,

    /** Current sending chain key (32 bytes) */
    val sendingChainKey: ByteArray,

    /** Number of messages sent with current sending chain */
    val sendingChainLength: Int,

    /** Current receiving chain key (32 bytes, null if haven't received yet) */
    val receivingChainKey: ByteArray?,

    /** Number of messages received with current receiving chain */
    val receivingChainLength: Int,

    /** Our current DH ratchet private key (32 bytes) */
    val ourDHRatchetPrivate: ByteArray,

    /** Our current DH ratchet public key (32 bytes) */
    val ourDHRatchetPublic: ByteArray,

    /** Their DH ratchet public key (32 bytes, null if haven't received) */
    val theirDHRatchetPublic: ByteArray?,

    /**
     * Skipped message keys for out-of-order delivery.
     *
     * 🚨 CRITICAL: Mobile networks are unreliable - messages WILL arrive out of order!
     * Without this, users will see "Failed to decrypt" errors constantly.
     *
     * Map key format: "{chainKeyHash}-{messageNumber}"
     * Map value: messageKey (32 bytes)
     *
     * Limit: Max 1000 keys (prevent memory exhaustion attacks)
     */
    val skippedMessageKeys: Map<String, ByteArray> = emptyMap(),

    val createdAt: Long = System.currentTimeMillis(),
    val lastRatchetAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MAX_SKIPPED_MESSAGES = 1000
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RatchetSession

        return contactId == other.contactId
    }

    override fun hashCode(): Int {
        return contactId.hashCode()
    }
}
```

#### File 2: `EncryptedMessageV2.kt` (NEW)

**Location:** `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/EncryptedMessageV2.kt`

```kotlin
package com.void.block.messaging.crypto

import kotlinx.serialization.Serializable

/**
 * Encrypted message envelope for Double Ratchet (V2).
 */
@Serializable
data class EncryptedMessageV2(
    /** Sender's current DH ratchet public key (32 bytes) */
    val dhPublicKey: ByteArray,

    /** Length of previous sending chain (for skipped message keys) */
    val previousChainLength: Int,

    /** Message number in current sending chain */
    val messageNumber: Int,

    /** Encrypted message content */
    val ciphertext: ByteArray,

    /** Random nonce (12 bytes for GCM) */
    val nonce: ByteArray,

    /** HMAC-SHA256 authentication tag */
    val mac: ByteArray,

    val version: Int = 2
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedMessageV2

        if (!dhPublicKey.contentEquals(other.dhPublicKey)) return false
        if (previousChainLength != other.previousChainLength) return false
        if (messageNumber != other.messageNumber) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!mac.contentEquals(other.mac)) return false
        if (version != other.version) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dhPublicKey.contentHashCode()
        result = 31 * result + previousChainLength
        result = 31 * result + messageNumber
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + mac.contentHashCode()
        result = 31 * result + version
        return result
    }
}
```

#### File 3: `RatchetKDF.kt` (NEW)

**Location:** `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/RatchetKDF.kt`

```kotlin
package com.void.block.messaging.crypto

import com.void.slate.crypto.CryptoProvider

/**
 * Key Derivation Functions for Double Ratchet.
 */
class RatchetKDF(
    private val crypto: CryptoProvider
) {
    /**
     * KDF_RK: Derive new root key and chain key from DH output.
     */
    suspend fun deriveRootKey(
        rootKey: ByteArray,
        dhOutput: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(rootKey.size == 32) { "Root key must be 32 bytes" }
        require(dhOutput.size == 32) { "DH output must be 32 bytes" }

        val combined = rootKey + dhOutput

        val newRootKey = crypto.derive(combined, "ratchet-root")
        val newChainKey = crypto.derive(combined, "ratchet-chain")

        return Pair(newRootKey, newChainKey)
    }

    /**
     * KDF_CK: Derive new chain key and message key from chain key.
     */
    suspend fun deriveChainKey(
        chainKey: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(chainKey.size == 32) { "Chain key must be 32 bytes" }

        val newChainKey = crypto.derive(chainKey, "chain-key")
        val messageKey = crypto.derive(chainKey, "message-key")

        return Pair(newChainKey, messageKey)
    }

    /**
     * Derive encryption and MAC keys from message key.
     */
    suspend fun deriveMessageKeys(
        messageKey: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(messageKey.size == 32) { "Message key must be 32 bytes" }

        val encryptionKey = crypto.derive(messageKey, "encrypt")
        val macKey = crypto.derive(messageKey, "mac")

        return Pair(encryptionKey, macKey)
    }
}
```

#### File 4: `DoubleRatchet.kt` (NEW)

**Location:** `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/DoubleRatchet.kt`

```kotlin
package com.void.block.messaging.crypto

import com.void.slate.crypto.CryptoProvider
import com.void.slate.storage.SecureStorage

/**
 * Double Ratchet implementation following Signal Protocol specification.
 *
 * 🚨 CRITICAL FEATURES INCLUDED:
 * 1. Skipped message handling (for out-of-order delivery)
 * 2. Proper HMAC via CryptoProvider (not manual)
 * 3. Standard ping-pong DH ratchet (no forced counter)
 */
class DoubleRatchet(
    private val crypto: CryptoProvider,
    private val kdf: RatchetKDF,
    private val storage: SecureStorage
) {

    /**
     * Initialize a new ratchet session as sender (Alice).
     */
    suspend fun initializeSession(
        contactId: String,
        sharedSecret: ByteArray,
        theirPublicKey: ByteArray
    ): RatchetSession {
        val rootKey = crypto.derive(sharedSecret, "ratchet-init-root")

        val ourDHKeyPair = crypto.generateKeyPair()

        val dhOutput = crypto.computeSharedSecret(ourDHKeyPair.privateKey, theirPublicKey)
        val (newRootKey, sendingChainKey) = kdf.deriveRootKey(rootKey, dhOutput)

        val session = RatchetSession(
            contactId = contactId,
            rootKey = newRootKey,
            sendingChainKey = sendingChainKey,
            sendingChainLength = 0,
            receivingChainKey = null,
            receivingChainLength = 0,
            ourDHRatchetPrivate = ourDHKeyPair.privateKey,
            ourDHRatchetPublic = ourDHKeyPair.publicKey,
            theirDHRatchetPublic = theirPublicKey
        )

        saveSession(session)
        return session
    }

    /**
     * Initialize a new ratchet session as receiver (Bob).
     */
    suspend fun acceptSession(
        contactId: String,
        sharedSecret: ByteArray
    ): RatchetSession {
        val rootKey = crypto.derive(sharedSecret, "ratchet-init-root")

        val ourDHKeyPair = crypto.generateKeyPair()

        val session = RatchetSession(
            contactId = contactId,
            rootKey = rootKey,
            sendingChainKey = ByteArray(32),
            sendingChainLength = 0,
            receivingChainKey = null,
            receivingChainLength = 0,
            ourDHRatchetPrivate = ourDHKeyPair.privateKey,
            ourDHRatchetPublic = ourDHKeyPair.publicKey,
            theirDHRatchetPublic = null
        )

        saveSession(session)
        return session
    }

    /**
     * Encrypt a message using the current ratchet state.
     */
    suspend fun encryptMessage(
        contactId: String,
        plaintext: ByteArray
    ): EncryptedMessageV2 {
        val session = loadSession(contactId)
            ?: throw IllegalStateException("No ratchet session for contact $contactId")

        // SYMMETRIC RATCHET: Derive new chain key and message key
        val (newChainKey, messageKey) = kdf.deriveChainKey(session.sendingChainKey)

        // Derive encryption and MAC keys
        val (encKey, macKey) = kdf.deriveMessageKeys(messageKey)

        // Encrypt the message
        val encrypted = crypto.encrypt(plaintext, encKey)

        // 🆕 Compute HMAC using CryptoProvider (not manual!)
        val macInput = session.ourDHRatchetPublic + encrypted.ciphertext + encrypted.nonce
        val mac = crypto.hmacSha256(macKey, macInput)

        val message = EncryptedMessageV2(
            dhPublicKey = session.ourDHRatchetPublic,
            previousChainLength = session.receivingChainLength,
            messageNumber = session.sendingChainLength,
            ciphertext = encrypted.ciphertext,
            nonce = encrypted.nonce,
            mac = mac
        )

        // Update session state
        val updatedSession = session.copy(
            sendingChainKey = newChainKey,
            sendingChainLength = session.sendingChainLength + 1
        )
        saveSession(updatedSession)

        return message
    }

    /**
     * Decrypt a message using the current ratchet state.
     *
     * 🚨 INCLUDES SKIPPED MESSAGE HANDLING!
     */
    suspend fun decryptMessage(
        contactId: String,
        encrypted: EncryptedMessageV2
    ): ByteArray {
        var session = loadSession(contactId)
            ?: throw IllegalStateException("No ratchet session for contact $contactId")

        // Check if we need to perform DH ratchet step
        if (session.theirDHRatchetPublic == null ||
            !session.theirDHRatchetPublic.contentEquals(encrypted.dhPublicKey)
        ) {
            session = performDHRatchet(session, encrypted.dhPublicKey)
        }

        // 🆕 CHECK FOR SKIPPED MESSAGE KEY FIRST
        val chainKeyHash = session.receivingChainKey?.toHexString() ?: ""
        val skippedKeyId = "$chainKeyHash-${encrypted.messageNumber}"

        if (session.skippedMessageKeys.containsKey(skippedKeyId)) {
            // This is a previously skipped message!
            return decryptWithSkippedKey(session, encrypted, skippedKeyId)
        }

        // 🆕 Handle skipped messages (store keys for messages we haven't received yet)
        val skippedKeys = handleSkippedMessages(
            session,
            encrypted.previousChainLength,
            encrypted.messageNumber
        )

        // SYMMETRIC RATCHET: Derive chain key up to message number
        var chainKey = session.receivingChainKey
            ?: throw IllegalStateException("Receiving chain key not initialized")

        // Derive message key for THIS message
        val (newChainKey, messageKey) = kdf.deriveChainKey(chainKey)

        // Derive encryption and MAC keys
        val (encKey, macKey) = kdf.deriveMessageKeys(messageKey)

        // Verify HMAC
        val macInput = encrypted.dhPublicKey + encrypted.ciphertext + encrypted.nonce
        val expectedMac = crypto.hmacSha256(macKey, macInput)
        if (!encrypted.mac.contentEquals(expectedMac)) {
            throw MessageDecryptionException("MAC verification failed")
        }

        // Decrypt message
        val plaintext = crypto.decrypt(
            com.void.slate.crypto.EncryptedData(
                ciphertext = encrypted.ciphertext,
                nonce = encrypted.nonce
            ),
            encKey
        )

        // Update session state
        val updatedSession = session.copy(
            receivingChainKey = newChainKey,
            receivingChainLength = encrypted.messageNumber + 1,
            skippedMessageKeys = session.skippedMessageKeys + skippedKeys
        )
        saveSession(updatedSession)

        return plaintext
    }

    /**
     * 🆕 Decrypt a message using a previously stored skipped message key.
     */
    private suspend fun decryptWithSkippedKey(
        session: RatchetSession,
        encrypted: EncryptedMessageV2,
        skippedKeyId: String
    ): ByteArray {
        val messageKey = session.skippedMessageKeys[skippedKeyId]!!

        // Derive encryption and MAC keys
        val (encKey, macKey) = kdf.deriveMessageKeys(messageKey)

        // Verify MAC
        val macInput = encrypted.dhPublicKey + encrypted.ciphertext + encrypted.nonce
        val expectedMac = crypto.hmacSha256(macKey, macInput)
        if (!encrypted.mac.contentEquals(expectedMac)) {
            throw MessageDecryptionException("MAC verification failed for skipped message")
        }

        // Decrypt
        val plaintext = crypto.decrypt(
            com.void.slate.crypto.EncryptedData(
                ciphertext = encrypted.ciphertext,
                nonce = encrypted.nonce
            ),
            encKey
        )

        // Remove used key
        val updatedSession = session.copy(
            skippedMessageKeys = session.skippedMessageKeys - skippedKeyId
        )
        saveSession(updatedSession)

        return plaintext
    }

    /**
     * 🆕 Handle skipped messages (out-of-order delivery).
     *
     * 🚨 CRITICAL: Without this, decryption will fail constantly on real networks!
     *
     * When we receive message N but our chain is at position M (M < N),
     * we need to store keys for messages M+1, M+2, ..., N-1.
     */
    private suspend fun handleSkippedMessages(
        session: RatchetSession,
        previousChainLength: Int,
        messageNumber: Int
    ): Map<String, ByteArray> {
        val skippedKeys = mutableMapOf<String, ByteArray>()

        // If no gap, nothing to skip
        if (messageNumber <= session.receivingChainLength) {
            return emptyMap()
        }

        // Derive keys for all skipped messages
        var chainKey = session.receivingChainKey
            ?: throw IllegalStateException("No receiving chain key")

        for (i in session.receivingChainLength until messageNumber) {
            val (newChainKey, messageKey) = kdf.deriveChainKey(chainKey)

            // Store skipped message key with unique identifier
            val keyId = "${chainKey.toHexString()}-$i"
            skippedKeys[keyId] = messageKey

            chainKey = newChainKey

            // 🚨 Prevent memory exhaustion attacks
            if (skippedKeys.size + session.skippedMessageKeys.size > RatchetSession.MAX_SKIPPED_MESSAGES) {
                throw MessageDecryptionException("Too many skipped messages (possible DoS attack)")
            }
        }

        return skippedKeys
    }

    /**
     * Perform DH ratchet step (ping-pong behavior).
     */
    private suspend fun performDHRatchet(
        session: RatchetSession,
        theirNewDHPublic: ByteArray
    ): RatchetSession {
        // Perform ECDH with their new public key
        val dhOutput = crypto.computeSharedSecret(
            session.ourDHRatchetPrivate,
            theirNewDHPublic
        )

        // Derive new root key and receiving chain key
        val (newRootKey, receivingChainKey) = kdf.deriveRootKey(session.rootKey, dhOutput)

        // Generate our NEW DH key pair
        val ourNewDHKeyPair = crypto.generateKeyPair()

        // Derive new sending chain key
        val dhOutput2 = crypto.computeSharedSecret(
            ourNewDHKeyPair.privateKey,
            theirNewDHPublic
        )
        val (newRootKey2, sendingChainKey) = kdf.deriveRootKey(newRootKey, dhOutput2)

        return session.copy(
            rootKey = newRootKey2,
            sendingChainKey = sendingChainKey,
            sendingChainLength = 0,
            receivingChainKey = receivingChainKey,
            receivingChainLength = 0,
            ourDHRatchetPrivate = ourNewDHKeyPair.privateKey,
            ourDHRatchetPublic = ourNewDHKeyPair.publicKey,
            theirDHRatchetPublic = theirNewDHPublic,
            lastRatchetAt = System.currentTimeMillis()
        )
    }

    private suspend fun saveSession(session: RatchetSession) {
        val key = "ratchet.session.${session.contactId}"
        val serialized = kotlinx.serialization.json.Json.encodeToString(
            RatchetSession.serializer(),
            session
        )
        storage.putString(key, serialized)
    }

    private suspend fun loadSession(contactId: String): RatchetSession? {
        val key = "ratchet.session.$contactId"
        val serialized = storage.getString(key) ?: return null

        return kotlinx.serialization.json.Json.decodeFromString(
            RatchetSession.serializer(),
            serialized
        )
    }

    // Helper extension
    private fun ByteArray.toHexString(): String {
        return this.joinToString("") { "%02x".format(it) }
    }
}
```

---

## 📊 Summary of Files

### Modified Files (6 files)

1. `blocks/identity/src/main/kotlin/com/void/block/identity/data/IdentityRepository.kt` (add mailboxSeed)
2. `slate/network/src/main/kotlin/com/void/slate/network/mailbox/MailboxDerivation.kt` (use mailboxSeed)
3. `blocks/contacts/src/main/kotlin/com/void/block/contacts/domain/Contact.kt` (change to mailboxSeed)
4. `slate/network/src/main/kotlin/com/void/slate/network/supabase/MessageSender.kt` (use mailboxSeed)
5. `blocks/contacts/src/main/kotlin/com/void/block/contacts/ui/viewmodels/AddContactViewModel.kt` (fix bug)
6. `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/MessageEncryption.kt` (version byte)

### Modified Interfaces (2 files)

7. `slate/core/src/main/kotlin/com/void/slate/crypto/CryptoProvider.kt` (add hmacSha256)
8. `slate/crypto/src/main/kotlin/com/void/slate/crypto/impl/TinkCryptoProvider.kt` (implement hmacSha256)

### New Files (4 files)

9. `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/RatchetSession.kt`
10. `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/EncryptedMessageV2.kt`
11. `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/RatchetKDF.kt`
12. `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/DoubleRatchet.kt`

---

## 🚀 Implementation Order

### Priority 1: Security Critical (Day 1)

1. ✅ **Key Derivation Fix** (2 hours)
   - Modify 5 files: IdentityRepository, MailboxDerivation, Contact, MessageSender, AddContactViewModel
   - MUST DO FIRST - security vulnerability

### Priority 2: Foundation (Day 1)

2. ✅ **Add HMAC to CryptoProvider** (15 minutes)
   - Modify CryptoProvider interface and TinkCryptoProvider implementation
   - Required before Double Ratchet

3. ✅ **Protocol Version Byte** (30 minutes)
   - Modify MessageEncryption.kt
   - Foundation for V2 messages

### Priority 3: Core Feature (Days 2-3)

4. ✅ **Double Ratchet Implementation** (6-8 hours)
   - Create 4 new files: RatchetSession, EncryptedMessageV2, RatchetKDF, DoubleRatchet
   - Includes skipped message handling (NOT a TODO!)
   - Uses CryptoProvider HMAC (not manual)

---

## 🧪 Testing Checklist

### Part 1: Key Derivation

- [ ] Generate new identity → verify mailboxSeed created
- [ ] Derive mailbox from mailboxSeed → verify address
- [ ] Create QR code with mailboxSeed → verify JSON
- [ ] Scan QR code → verify contact added
- [ ] Send message using mailboxSeed → verify delivery
- [ ] **CRITICAL:** Verify mailboxSeed CANNOT derive private keys

### Part 2: HMAC

- [ ] Compute HMAC via CryptoProvider → verify 32-byte output
- [ ] Verify HMAC matches test vectors
- [ ] Benchmark: CryptoProvider HMAC vs manual (should be faster)

### Part 3: Double Ratchet

- [ ] Initialize session → verify keys generated
- [ ] Encrypt message → verify V2 format with version byte
- [ ] Decrypt message → verify plaintext recovered
- [ ] Send reply → verify DH ratchet step
- [ ] **CRITICAL:** Test out-of-order delivery:
  - Send messages 1, 2, 3
  - Deliver in order: 3, 1, 2
  - Verify all decrypt successfully
- [ ] Test skipped message limit (send 1001 messages, expect error)
- [ ] Verify forward secrecy (delete old chain keys)

---

## 🎯 Critical Success Criteria

### Must Have (Before Any Testing)

1. ✅ mailboxSeed CANNOT derive private keys (verify with test)
2. ✅ Out-of-order message decryption works (test with network simulator)
3. ✅ HMAC via CryptoProvider (no manual crypto)

### Nice to Have (Phase 3.1)

- QR code UI implementation
- Session reset protocol
- Ratchet session expiry (90 days)

---

**Status:** Ready for implementation!
**Blocking Issues:** None
**Next Step:** Start with Part 1 (Key Derivation Fix)
