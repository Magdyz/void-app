package com.void.server.routes

import com.void.server.models.ErrorResponse
import com.void.server.models.SyncAck
import com.void.server.security.SignatureVerification
import com.void.server.services.RedisService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * WebSocket sync routes.
 *
 * WS /v1/sync/stream - Real-time encrypted message sync
 */
fun Route.syncRoutes(redisService: RedisService) {

    /**
     * Endpoint: WS /v1/sync/stream
     *
     * WebSocket endpoint for real-time message synchronization.
     *
     * Flow:
     * 1. Client connects with Authorization header (Bearer <Signed-JWT>)
     * 2. Verify signature on the JWT/timestamp
     * 3. Push all pending encrypted blobs from offline queue
     * 4. Listen for ACK messages from client
     * 5. Delete messages from server upon receiving ACK
     * 6. Maintain connection for real-time delivery
     *
     * STRICT CONSTRAINT:
     * - Upon receiving ACK, immediately delete messages
     * - We are a relay, not a cloud archive
     *
     * Security:
     * - Random jitter on responses (50-200ms) to blur timing correlations
     * - Generic error messages only
     */
    webSocket("/sync/stream") {
        var authenticatedAccountId: String? = null

        try {
            // Apply initial timing jitter
            SignatureVerification.applyTimingJitter()

            // Extract Authorization header
            val authHeader = call.request.headers["Authorization"]
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn { "WebSocket auth failed: Missing or invalid Authorization header" }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            // Parse the JWT/signed token (simplified - in production, use proper JWT)
            // Format: "Bearer <accountId>:<timestamp>:<signature>"
            val token = authHeader.removePrefix("Bearer ")
            val parts = token.split(":")

            if (parts.size != 3) {
                logger.warn { "WebSocket auth failed: Invalid token format" }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            val accountId = parts[0]
            val timestamp = parts[1].toLongOrNull()
            val signature = parts[2]

            if (timestamp == null) {
                logger.warn { "WebSocket auth failed: Invalid timestamp" }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            // Verify signature
            val isValid = SignatureVerification.verifyTimestampedRequest(
                timestamp = timestamp,
                signature = signature,
                accountId = accountId
            )

            if (!isValid) {
                logger.warn { "WebSocket auth failed: Invalid signature" }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            // Authentication successful
            authenticatedAccountId = accountId
            logger.info { "WebSocket authenticated for account" }

            // Fetch all pending messages from offline queue
            val pendingMessages = redisService.getPendingMessages(accountId)
            logger.info { "Found ${pendingMessages.size} pending messages" }

            // Push all pending messages down the WebSocket
            for (message in pendingMessages) {
                // Apply timing jitter to blur correlations
                SignatureVerification.applyTimingJitter()

                val messageJson = json.encodeToString(message)
                send(Frame.Text(messageJson))
                logger.info { "Sent message: ${message.messageId}" }
            }

            // Send a "sync complete" marker
            send(Frame.Text(json.encodeToString(mapOf("sync" to "complete"))))

            // Listen for ACK messages and real-time messages
            incoming.consumeEach { frame ->
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()

                        try {
                            // Parse ACK message
                            val ack = json.decodeFromString<SyncAck>(text)

                            // Delete the acknowledged message from server
                            redisService.deleteMessage(accountId, ack.messageId)
                            logger.info { "Message acknowledged and deleted: ${ack.messageId}" }

                            // Apply timing jitter
                            SignatureVerification.applyTimingJitter()

                            // Send confirmation
                            send(Frame.Text(json.encodeToString(mapOf("ack_received" to ack.messageId))))

                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to parse ACK message: $text" }
                        }
                    }
                    is Frame.Close -> {
                        logger.info { "WebSocket closed by client" }
                    }
                    else -> {
                        logger.debug { "Received non-text frame, ignoring" }
                    }
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "WebSocket error" }
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Internal error"))
        } finally {
            logger.info { "WebSocket connection closed for account: $authenticatedAccountId" }
        }
    }

    /**
     * Health check endpoint for sync service.
     */
    get("/sync/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "healthy", "service" to "sync"))
    }
}
