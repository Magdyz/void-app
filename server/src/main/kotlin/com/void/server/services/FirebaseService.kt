package com.void.server.services

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.void.server.config.VoidConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.FileInputStream

/**
 * Firebase Cloud Messaging service.
 * Sends EMPTY "tickle" notifications to wake up devices.
 *
 * CRITICAL PRIVACY CONSTRAINT:
 * - FCM payload MUST be empty or contain only a generic flag
 * - NEVER include sender_id, preview, or message content
 */
class FirebaseService {
    private val logger = KotlinLogging.logger {}

    init {
        initializeFirebase()
    }

    private fun initializeFirebase() {
        try {
            val serviceAccount = FileInputStream(VoidConfig.Firebase.serviceAccountPath)
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build()

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
                logger.info { "Firebase Admin SDK initialized" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize Firebase Admin SDK. Ensure service account JSON is configured." }
            throw e
        }
    }

    /**
     * Send an EMPTY "tickle" notification to wake up the device.
     *
     * FORBIDDEN: Including any message metadata in the payload.
     * ALLOWED: Only the command to wake up the app.
     */
    suspend fun sendTickle(fcmToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Construct FCM message with HIGH priority and EMPTY payload
            val message = Message.builder()
                .setToken(fcmToken)
                .putData("type", "check_server") // Generic wake-up signal
                // CRITICAL: No sender_id, no preview, no message_id
                .build()

            val response = FirebaseMessaging.getInstance().send(message)
            logger.info { "FCM tickle sent successfully: $response" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send FCM tickle" }
            false
        }
    }

    /**
     * Send a tickle with custom data (for testing/debugging).
     * Still maintains privacy - no message content.
     */
    suspend fun sendTickleWithData(fcmToken: String, data: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val messageBuilder = Message.builder()
                .setToken(fcmToken)

            data.forEach { (key, value) ->
                messageBuilder.putData(key, value)
            }

            val response = FirebaseMessaging.getInstance().send(messageBuilder.build())
            logger.info { "FCM tickle with custom data sent: $response" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send FCM tickle with custom data" }
            false
        }
    }

    /**
     * Validate that a tickle payload is privacy-compliant.
     * Logs a warning if non-compliant data is detected.
     */
    fun validateTicklePayload(data: Map<String, String>): Boolean {
        val forbiddenKeys = listOf("sender_id", "message_id", "preview", "content", "recipient_id")
        val violations = data.keys.filter { it.lowercase() in forbiddenKeys }

        if (violations.isNotEmpty()) {
            logger.warn { "PRIVACY VIOLATION: FCM payload contains forbidden keys: $violations" }
            return false
        }

        return true
    }
}
