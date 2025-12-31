package com.void.server.routes

import com.void.server.models.EncryptedMessage
import com.void.server.models.SendMessageRequest
import com.void.server.models.SendMessageResponse
import com.void.server.models.ErrorResponse
import com.void.server.security.SignatureVerification
import com.void.server.services.FirebaseService
import com.void.server.services.RedisService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Message sending routes.
 *
 * POST /v1/messages/send - Send an encrypted message
 */
fun Route.messageRoutes(redisService: RedisService, firebaseService: FirebaseService) {

    /**
     * Endpoint: POST /v1/messages/send
     *
     * Receives an encrypted message and triggers the "tickle" logic.
     *
     * Flow:
     * 1. Verify sender's signature
     * 2. Store encrypted message in recipient's queue
     * 3. Look up recipient's FCM token
     * 4. Send EMPTY tickle via FCM (HIGH priority)
     *
     * CRITICAL PRIVACY CONSTRAINT:
     * - FCM payload MUST be empty or contain only generic flag
     * - FORBIDDEN: sender_id, preview, message_id in push payload
     * - ALLOWED: Only the wake-up command
     */
    post("/messages/send") {
        // Apply timing jitter
        SignatureVerification.applyTimingJitter()

        try {
            val request = call.receive<SendMessageRequest>()

            // Verify sender's signature
            val isValid = SignatureVerification.verifyTimestampedRequest(
                timestamp = request.timestamp,
                signature = request.signature,
                accountId = request.senderId
            )

            if (!isValid) {
                logger.warn { "Message send failed: Invalid signature" }
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse())
                return@post
            }

            // Generate unique message ID
            val messageId = UUID.randomUUID().toString()

            // Create encrypted message blob
            val encryptedMessage = EncryptedMessage(
                messageId = messageId,
                senderId = request.senderId,
                recipientId = request.recipientId,
                encryptedPayload = request.encryptedPayload,
                timestamp = System.currentTimeMillis()
            )

            // Store message in recipient's offline queue
            redisService.queueMessage(encryptedMessage)

            // Look up recipient's FCM token
            val fcmToken = redisService.getFcmToken(request.recipientId)

            if (fcmToken != null) {
                // Send EMPTY tickle to wake up recipient's device
                val tickleSent = firebaseService.sendTickle(fcmToken)

                if (tickleSent) {
                    logger.info { "FCM tickle sent for message: $messageId" }
                } else {
                    logger.warn { "Failed to send FCM tickle for message: $messageId" }
                }
            } else {
                logger.info { "No FCM token found for recipient. Message queued for later sync." }
            }

            // Success response
            call.respond(
                HttpStatusCode.OK,
                SendMessageResponse(
                    success = true,
                    messageId = messageId
                )
            )

        } catch (e: Exception) {
            logger.error(e) { "Error processing message send" }
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse())
        }
    }

    /**
     * Endpoint: GET /v1/messages
     *
     * Fetch pending messages for the authenticated user (REST polling).
     *
     * Query Parameters:
     * - since: Optional timestamp to fetch messages after this time
     *
     * Note: This is a fallback for when WebSocket isn't available.
     * The primary sync method should be WS /v1/sync/stream.
     *
     * TODO: Add proper authentication (client needs to send account ID or JWT)
     */
    get("/messages") {
        // Apply timing jitter
        SignatureVerification.applyTimingJitter()

        try {
            // Extract authentication from headers
            // TODO: Client needs to send X-Account-ID header or implement proper session/JWT auth
            val accountId = call.request.headers["X-Account-ID"]
            val since = call.request.queryParameters["since"]?.toLongOrNull()

            if (accountId == null) {
                // For now, return empty array to avoid breaking the client
                // TODO: Implement proper authentication
                logger.warn { "GET /messages: No X-Account-ID header, returning empty array" }
                call.respond(HttpStatusCode.OK, emptyList<EncryptedMessage>())
                return@get
            }

            // Fetch pending messages from Redis queue
            val pendingMessages = redisService.getPendingMessages(accountId)

            // Filter by timestamp if provided
            val filteredMessages = if (since != null) {
                pendingMessages.filter { it.timestamp > since }
            } else {
                pendingMessages
            }

            // Return messages as JSON array
            call.respond(HttpStatusCode.OK, filteredMessages)
            logger.info { "Retrieved ${filteredMessages.size} pending messages for account" }

        } catch (e: Exception) {
            logger.error(e) { "Error fetching messages" }
            // Return empty array instead of error to avoid breaking client
            call.respond(HttpStatusCode.OK, emptyList<EncryptedMessage>())
        }
    }

    /**
     * Health check endpoint for messaging service.
     */
    get("/messages/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "healthy", "service" to "messaging"))
    }
}
