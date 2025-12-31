package com.void.server.routes

import com.void.server.models.DeviceRegistrationRequest
import com.void.server.models.DeviceRegistrationResponse
import com.void.server.models.ErrorResponse
import com.void.server.security.SignatureVerification
import com.void.server.services.RedisService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Device registration routes.
 *
 * POST /v1/device/register - Register FCM token for a device
 */
fun Route.deviceRoutes(redisService: RedisService) {

    /**
     * Endpoint: POST /v1/device/register
     *
     * Registers a device's FCM token for push notifications.
     *
     * PRIVACY CONSTRAINTS:
     * - Do NOT log IP addresses
     * - Do NOT link FCM token to any other identity data
     * - If previous token exists, overwrite silently
     *
     * Security:
     * - Signature verification using account's public key
     * - Timestamp validation to prevent replay attacks
     */
    post("/device/register") {
        // Apply timing jitter to prevent timing attacks
        SignatureVerification.applyTimingJitter()

        try {
            val request = call.receive<DeviceRegistrationRequest>()

            // Validate signature
            val isValid = SignatureVerification.verifyTimestampedRequest(
                timestamp = request.timestamp,
                signature = request.signature,
                accountId = request.accountId
            )

            if (!isValid) {
                // Generic error - don't reveal why verification failed
                logger.warn { "Device registration failed: Invalid signature" }
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse())
                return@post
            }

            // Store FCM token in Redis
            redisService.storeFcmToken(request.accountId, request.fcmToken)

            // Success response
            call.respond(HttpStatusCode.OK, DeviceRegistrationResponse(success = true))
            logger.info { "Device registered successfully" }

        } catch (e: Exception) {
            // Generic error - don't leak internal details
            logger.error(e) { "Error processing device registration" }
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse())
        }
    }

    /**
     * Health check endpoint for device registration service.
     */
    get("/device/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "healthy", "service" to "device-registration"))
    }
}
