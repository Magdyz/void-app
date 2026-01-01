package com.void.block.contacts.domain

import kotlinx.serialization.Serializable

/**
 * Represents a contact in the VOID network.
 *
 * Contacts are identified by their 3-word identity and public key.
 * All communication is end-to-end encrypted using their public key.
 */
@Serializable
data class Contact(
    val id: String,                          // UUID for internal reference
    val identity: ThreeWordIdentity,          // Their 3-word VOID identity
    val displayName: String?,                 // Optional nickname
    val publicKey: ByteArray,                 // Their X25519 public key for encryption
    val identityKey: ByteArray,               // Their Ed25519 identity key
    val identitySeed: ByteArray,              // Their identity seed (for mailbox derivation)
    val verified: Boolean = false,            // Have we verified their key in person?
    val blocked: Boolean = false,             // Is this contact blocked?
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,            // Last time we got a message from them
    val fingerprint: String = "",             // Key fingerprint for verification
) {
    /**
     * Get display name or fallback to identity.
     */
    fun getDisplayNameOrIdentity(): String {
        return displayName ?: identity.toString()
    }

    /**
     * Generate key fingerprint for verification.
     * Uses first 64 bits of identity key hash.
     */
    fun generateFingerprint(): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identityKey)
        return hash.take(8)
            .joinToString("") { "%02x".format(it) }
            .chunked(4)
            .joinToString("-")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Contact

        if (id != other.id) return false
        if (identity != other.identity) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (!identityKey.contentEquals(other.identityKey)) return false
        if (!identitySeed.contentEquals(other.identitySeed)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + identityKey.contentHashCode()
        result = 31 * result + identitySeed.contentHashCode()
        return result
    }
}

/**
 * Three-word identity (same as blocks/identity).
 * Kept here to avoid cross-block dependencies.
 */
@Serializable
data class ThreeWordIdentity(
    val word1: String,
    val word2: String,
    val word3: String
) {
    override fun toString(): String = "$word1.$word2.$word3"

    companion object {
        /**
         * Parse a three-word identity from string.
         * Format: "word1.word2.word3"
         */
        fun parse(identity: String): ThreeWordIdentity? {
            val parts = identity.split(".")
            if (parts.size != 3) return null
            if (parts.any { it.isBlank() }) return null
            return ThreeWordIdentity(
                parts[0].lowercase(),
                parts[1].lowercase(),
                parts[2].lowercase()
            )
        }

        /**
         * Validate a three-word identity string.
         */
        fun isValid(identity: String): Boolean {
            return parse(identity) != null
        }
    }
}

/**
 * Contact request sent/received before adding a contact.
 */
@Serializable
data class ContactRequest(
    val id: String,
    val fromIdentity: ThreeWordIdentity,
    val publicKey: ByteArray,
    val identityKey: ByteArray,
    val identitySeed: ByteArray,              // Identity seed for mailbox derivation
    val message: String = "",                 // Optional introduction message
    val timestamp: Long = System.currentTimeMillis(),
    val status: RequestStatus = RequestStatus.PENDING
) {
    enum class RequestStatus {
        PENDING,
        ACCEPTED,
        REJECTED,
        EXPIRED
    }
}

/**
 * QR code data for contact exchange.
 */
@Serializable
data class ContactQRData(
    val identity: ThreeWordIdentity,
    val publicKey: String,                    // Base64 encoded
    val identityKey: String,                  // Base64 encoded
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Convert to JSON for QR code.
     */
    fun toJson(): String {
        return kotlinx.serialization.json.Json.encodeToString(serializer(), this)
    }

    companion object {
        /**
         * Parse QR code JSON data.
         */
        fun fromJson(json: String): ContactQRData? {
            return try {
                kotlinx.serialization.json.Json.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                null
            }
        }
    }
}
