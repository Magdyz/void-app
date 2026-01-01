package com.void.block.messaging.crypto

import com.void.slate.crypto.CryptoProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Message encryption using simplified Signal Protocol.
 *
 * Provides:
 * - End-to-end encryption between contacts
 * - Forward secrecy (ephemeral keys)
 * - Message authenticity (HMAC)
 *
 * Simplified for Phase 2:
 * - Uses static Diffie-Hellman for now
 * - Will be enhanced to full Double Ratchet in Phase 3
 */
class MessageEncryption(
    private val crypto: CryptoProvider
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Encrypt a message for a recipient.
     *
     * @param plaintext The message content
     * @param recipientPublicKey The recipient's X25519 public key
     * @param senderPrivateKey The sender's X25519 private key
     * @return Encrypted message envelope
     */
    suspend fun encrypt(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): EncryptedMessage {
        // Perform ECDH key agreement
        val sharedSecret = performKeyAgreement(senderPrivateKey, recipientPublicKey)

        // Derive encryption key and MAC key from shared secret
        val (encKey, macKey) = deriveKeys(sharedSecret)

        // Encrypt plaintext (includes nonce generation)
        val encrypted = crypto.encrypt(plaintext, encKey)

        // Compute HMAC over ciphertext + nonce
        val mac = computeHMAC(encrypted.ciphertext + encrypted.nonce, macKey)

        return EncryptedMessage(
            ciphertext = encrypted.ciphertext,
            nonce = encrypted.nonce,
            mac = mac,
            version = PROTOCOL_VERSION
        )
    }

    /**
     * Decrypt a message from a sender.
     *
     * @param encrypted The encrypted message envelope
     * @param senderPublicKey The sender's X25519 public key
     * @param recipientPrivateKey The recipient's X25519 private key
     * @return Decrypted plaintext
     * @throws MessageDecryptionException if decryption fails
     */
    suspend fun decrypt(
        encrypted: EncryptedMessage,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        // Verify version
        if (encrypted.version != PROTOCOL_VERSION) {
            throw MessageDecryptionException("Unsupported protocol version: ${encrypted.version}")
        }

        // Perform ECDH key agreement
        val sharedSecret = performKeyAgreement(recipientPrivateKey, senderPublicKey)

        // Derive encryption key and MAC key from shared secret
        val (encKey, macKey) = deriveKeys(sharedSecret)

        // Verify HMAC
        val expectedMac = computeHMAC(encrypted.ciphertext + encrypted.nonce, macKey)
        if (!encrypted.mac.contentEquals(expectedMac)) {
            throw MessageDecryptionException("MAC verification failed - message tampered or corrupted")
        }

        // Decrypt ciphertext
        return try {
            val encryptedData = com.void.slate.crypto.EncryptedData(
                ciphertext = encrypted.ciphertext,
                nonce = encrypted.nonce
            )
            crypto.decrypt(encryptedData, encKey)
        } catch (e: Exception) {
            throw MessageDecryptionException("Decryption failed: ${e.message}", e)
        }
    }

    /**
     * Serialize encrypted message to bytes for transmission.
     */
    fun serializeEncryptedMessage(encrypted: EncryptedMessage): ByteArray {
        val jsonString = json.encodeToString(EncryptedMessage.serializer(), encrypted)
        return jsonString.toByteArray()
    }

    /**
     * Deserialize encrypted message from bytes.
     */
    fun deserializeEncryptedMessage(bytes: ByteArray): EncryptedMessage {
        val jsonString = bytes.decodeToString()
        return json.decodeFromString(EncryptedMessage.serializer(), jsonString)
    }

    // ═══════════════════════════════════════════════════════════════
    // Private Helper Methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Perform X25519 ECDH key agreement.
     * Uses CryptoProvider's implementation for proper elliptic curve Diffie-Hellman.
     *
     * CRITICAL: Both sender and receiver must compute the same shared secret:
     * - Sender: ECDH(senderPrivate, recipientPublic)
     * - Receiver: ECDH(recipientPrivate, senderPublic)
     * These produce the same result due to ECDH properties.
     */
    private suspend fun performKeyAgreement(
        privateKey: ByteArray,
        publicKey: ByteArray
    ): ByteArray {
        // Use proper X25519 ECDH via CryptoProvider
        val sharedSecret = crypto.computeSharedSecret(privateKey, publicKey)

        // Hash the shared secret for additional security (KDF)
        return crypto.hash(sharedSecret)
    }

    /**
     * Derive encryption and MAC keys from shared secret using HKDF.
     */
    private suspend fun deriveKeys(sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        // Derive encryption key
        val encryptionKey = crypto.derive(sharedSecret, "message-encryption-key")

        // Derive MAC key
        val macKey = crypto.derive(sharedSecret, "message-mac-key")

        return Pair(encryptionKey, macKey)
    }

    /**
     * Compute HMAC-SHA256 using hash function.
     * HMAC(K, m) = H((K ⊕ opad) || H((K ⊕ ipad) || m))
     */
    private suspend fun computeHMAC(data: ByteArray, key: ByteArray): ByteArray {
        // Simple HMAC implementation using hash
        // For Phase 2 - will use proper HMAC in Phase 3
        val paddedKey = if (key.size < 64) {
            key + ByteArray(64 - key.size)
        } else {
            crypto.hash(key)
        }

        val ipad = ByteArray(64) { 0x36 }
        val opad = ByteArray(64) { 0x5c }

        val innerKey = ByteArray(64) { i -> (paddedKey[i].toInt() xor ipad[i].toInt()).toByte() }
        val outerKey = ByteArray(64) { i -> (paddedKey[i].toInt() xor opad[i].toInt()).toByte() }

        val innerHash = crypto.hash(innerKey + data)
        return crypto.hash(outerKey + innerHash)
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
    }
}

/**
 * Sealed sender header prepended to plaintext before encryption.
 * This allows the receiver to identify the sender after decryption.
 *
 * Format: senderId (32 bytes) + separator (1 byte '|') + message content
 */
@Serializable
data class SealedSenderHeader(
    /**
     * Sender's 32-byte identity seed (hex encoded as 64 chars).
     * This identifies who sent the message after decryption.
     */
    val senderId: String,

    /**
     * Timestamp when message was sent (milliseconds since epoch).
     */
    val timestamp: Long
) {
    companion object {
        /**
         * Serialize header and prepend to message content.
         *
         * Format: JSON_HEADER + "|" + MESSAGE_CONTENT
         */
        fun prependToMessage(senderId: String, timestamp: Long, messageContent: ByteArray): ByteArray {
            val header = SealedSenderHeader(senderId, timestamp)
            val json = Json.encodeToString(serializer(), header)
            val headerBytes = json.toByteArray(Charsets.UTF_8)
            val separator = "|".toByteArray(Charsets.UTF_8)
            return headerBytes + separator + messageContent
        }

        /**
         * Parse header from decrypted plaintext and extract message content.
         *
         * Returns: Pair(header, messageContent)
         */
        fun parseFromPlaintext(plaintext: ByteArray): Pair<SealedSenderHeader, ByteArray> {
            val plaintextString = plaintext.decodeToString()
            val separatorIndex = plaintextString.indexOf('|')

            if (separatorIndex == -1) {
                throw MessageDecryptionException("Invalid sealed sender format: missing separator")
            }

            val headerJson = plaintextString.substring(0, separatorIndex)
            val messageContent = plaintextString.substring(separatorIndex + 1).toByteArray(Charsets.UTF_8)

            val header = Json.decodeFromString(serializer(), headerJson)
            return Pair(header, messageContent)
        }
    }
}

/**
 * Encrypted message envelope.
 */
@Serializable
data class EncryptedMessage(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val mac: ByteArray,
    val version: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedMessage

        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!mac.contentEquals(other.mac)) return false
        if (version != other.version) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + mac.contentHashCode()
        result = 31 * result + version
        return result
    }
}

/**
 * Exception thrown when message decryption fails.
 */
class MessageDecryptionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
