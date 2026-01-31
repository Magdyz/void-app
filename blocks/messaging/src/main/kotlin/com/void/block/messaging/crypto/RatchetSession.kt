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
