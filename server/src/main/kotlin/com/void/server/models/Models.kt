package com.void.server.models

import kotlinx.serialization.Serializable

/**
 * Request model for device registration.
 */
@Serializable
data class DeviceRegistrationRequest(
    val accountId: String,        // Public key (3-word identity derived from it)
    val fcmToken: String,          // Firebase Cloud Messaging token
    val signature: String,         // Signed timestamp to prove ownership
    val timestamp: Long            // Request timestamp
)

/**
 * Response model for device registration.
 */
@Serializable
data class DeviceRegistrationResponse(
    val success: Boolean,
    val message: String = "Device registered successfully"
)

/**
 * Request model for sending a message.
 */
@Serializable
data class SendMessageRequest(
    val recipientId: String,       // Recipient's account ID (public key)
    val encryptedPayload: String,  // Base64 encrypted message blob
    val senderId: String,          // Sender's account ID
    val signature: String,         // Signed request
    val timestamp: Long            // Message timestamp
)

/**
 * Response model for sending a message.
 */
@Serializable
data class SendMessageResponse(
    val success: Boolean,
    val messageId: String,
    val message: String = "Message queued for delivery"
)

/**
 * Model for encrypted message stored in queue.
 */
@Serializable
data class EncryptedMessage(
    val messageId: String,
    val senderId: String,
    val recipientId: String,
    val encryptedPayload: String,
    val timestamp: Long
)

/**
 * WebSocket message for sync acknowledgement.
 */
@Serializable
data class SyncAck(
    val messageId: String,
    val status: String = "received"
)

/**
 * Generic error response.
 */
@Serializable
data class ErrorResponse(
    val error: String = "Unauthorized",
    val code: Int = 401
)

/**
 * FCM payload for "tickle" notification.
 * CRITICAL: Must be empty or contain only generic flag.
 */
@Serializable
data class FcmTicklePayload(
    val tickle: Boolean = true
)

/**
 * WebSocket authentication message.
 */
@Serializable
data class WebSocketAuth(
    val accountId: String,
    val signature: String,
    val timestamp: Long
)
