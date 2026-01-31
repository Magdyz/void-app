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
 *
 * The Double Ratchet provides:
 * - Forward secrecy: Old keys cannot decrypt new messages
 * - Post-compromise security: New DH ratchet heals from key compromise
 * - Out-of-order message delivery: Skipped message keys allow decryption
 *
 * ## How It Works
 *
 * 1. **Symmetric Ratchet** (every message):
 *    - Before encrypting: Derive new chain key and message key from current chain key
 *    - Use message key to encrypt
 *    - Update chain key to new value
 *    - Chain length increments by 1
 *
 * 2. **DH Ratchet** (on receiving new DH public key):
 *    - Perform ECDH with their new public key
 *    - Derive new root key and receiving chain key
 *    - Generate our new DH key pair
 *    - Derive new sending chain key
 *    - Reset chain lengths to 0
 *
 * 3. **Skipped Messages** (out-of-order delivery):
 *    - If we receive message N but our chain is at M (M < N)
 *    - Store keys for messages M+1, M+2, ..., N-1 as "skipped"
 *    - When those messages arrive later, use the stored keys
 *    - Prevents "Failed to decrypt" errors on real networks
 */
class DoubleRatchet(
    private val crypto: CryptoProvider,
    private val kdf: RatchetKDF,
    private val storage: SecureStorage
) {

    /**
     * Initialize a new ratchet session as sender (Alice).
     *
     * Used when Alice starts a new conversation with Bob.
     * Alice knows Bob's public key from the contact exchange.
     *
     * @param contactId Contact identifier
     * @param sharedSecret Initial shared secret (from contact exchange)
     * @param theirPublicKey Bob's X25519 public key
     * @return New ratchet session
     */
    suspend fun initializeSession(
        contactId: String,
        sharedSecret: ByteArray,
        theirPublicKey: ByteArray
    ): RatchetSession {
        // Derive initial root key from shared secret
        val rootKey = crypto.derive(sharedSecret, "ratchet-init-root")

        // Generate our first DH key pair
        val ourDHKeyPair = crypto.generateKeyPair()

        // Perform first DH ratchet to get sending chain
        val dhOutput = crypto.computeSharedSecret(ourDHKeyPair.privateKey, theirPublicKey)
        val (newRootKey, sendingChainKey) = kdf.deriveRootKey(rootKey, dhOutput)

        val session = RatchetSession(
            contactId = contactId,
            rootKey = newRootKey,
            sendingChainKey = sendingChainKey,
            sendingChainLength = 0,
            receivingChainKey = null,  // Will be set when we receive their first message
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
     *
     * Used when Bob accepts a new conversation from Alice.
     * Bob waits for Alice's first message to complete the handshake.
     *
     * @param contactId Contact identifier
     * @param sharedSecret Initial shared secret (from contact exchange)
     * @return New ratchet session
     */
    suspend fun acceptSession(
        contactId: String,
        sharedSecret: ByteArray
    ): RatchetSession {
        // Derive initial root key from shared secret
        val rootKey = crypto.derive(sharedSecret, "ratchet-init-root")

        // Generate our first DH key pair
        val ourDHKeyPair = crypto.generateKeyPair()

        val session = RatchetSession(
            contactId = contactId,
            rootKey = rootKey,
            sendingChainKey = ByteArray(32),  // Will be set after first DH ratchet
            sendingChainLength = 0,
            receivingChainKey = null,  // Will be set when we receive first message
            receivingChainLength = 0,
            ourDHRatchetPrivate = ourDHKeyPair.privateKey,
            ourDHRatchetPublic = ourDHKeyPair.publicKey,
            theirDHRatchetPublic = null  // Will be set from first received message
        )

        saveSession(session)
        return session
    }

    /**
     * Encrypt a message using the current ratchet state.
     *
     * Process:
     * 1. Derive new chain key and message key (symmetric ratchet)
     * 2. Derive encryption and MAC keys from message key
     * 3. Encrypt plaintext with encryption key
     * 4. Compute HMAC over (DH public key + ciphertext + nonce)
     * 5. Update session state (new chain key, increment chain length)
     *
     * @param contactId Contact to send message to
     * @param plaintext Message content
     * @return Encrypted message envelope
     */
    suspend fun encryptMessage(
        contactId: String,
        plaintext: ByteArray
    ): EncryptedMessageV2 {
        val session = loadSession(contactId)
            ?: throw IllegalStateException("No ratchet session for contact $contactId")

        // SYMMETRIC RATCHET: Derive new chain key and message key
        val (newChainKey, messageKey) = kdf.deriveChainKey(session.sendingChainKey)

        // Derive encryption and MAC keys from message key
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
     *
     * Process:
     * 1. Check if we need to perform DH ratchet (new DH public key)
     * 2. Check if this is a previously skipped message
     * 3. Handle any skipped messages (store keys for messages we haven't received)
     * 4. Derive message key and decrypt
     * 5. Verify HMAC
     * 6. Update session state
     *
     * @param contactId Contact who sent the message
     * @param encrypted Encrypted message envelope
     * @return Decrypted plaintext
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
     *
     * This handles out-of-order delivery: If we receive messages 1, 3, 2,
     * when message 3 arrives we'll store key for message 2 as "skipped".
     * When message 2 arrives, we decrypt it with this stored key.
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
     *
     * Example:
     * - We're at message 0
     * - We receive message 3
     * - We need to store keys for messages 1 and 2
     * - When messages 1 and 2 arrive later, we can decrypt them
     *
     * @param session Current ratchet session
     * @param previousChainLength Unused (for future compatibility)
     * @param messageNumber Message number we're receiving
     * @return Map of skipped message keys
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
     *
     * This is called when we receive a message with a NEW DH public key.
     * It performs two ECDH operations:
     * 1. ECDH with their new public key → new receiving chain
     * 2. Generate our new key pair → ECDH with their key → new sending chain
     *
     * This provides forward secrecy and post-compromise security.
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

    /**
     * Save ratchet session to secure storage.
     */
    private suspend fun saveSession(session: RatchetSession) {
        val key = "ratchet.session.${session.contactId}"
        val serialized = kotlinx.serialization.json.Json.encodeToString(
            RatchetSession.serializer(),
            session
        )
        storage.putString(key, serialized)
    }

    /**
     * Load ratchet session from secure storage.
     */
    private suspend fun loadSession(contactId: String): RatchetSession? {
        val key = "ratchet.session.$contactId"
        val serialized = storage.getString(key) ?: return null

        return kotlinx.serialization.json.Json.decodeFromString(
            RatchetSession.serializer(),
            serialized
        )
    }

    /**
     * Helper extension to convert ByteArray to hex string.
     */
    private fun ByteArray.toHexString(): String {
        return this.joinToString("") { "%02x".format(it) }
    }
}
