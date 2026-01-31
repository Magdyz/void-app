package com.void.block.messaging.crypto

import kotlinx.serialization.Serializable

/**
 * Encrypted message envelope for Double Ratchet (V2).
 *
 * Contains all metadata needed for the Double Ratchet protocol:
 * - DH public key for ratcheting
 * - Chain metadata for skipped message handling
 * - Encrypted payload with nonce and MAC
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
