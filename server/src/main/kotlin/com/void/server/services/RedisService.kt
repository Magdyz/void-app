package com.void.server.services

import com.void.server.config.VoidConfig
import com.void.server.models.EncryptedMessage
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.Duration

/**
 * Redis service for ephemeral data storage.
 * Handles FCM token mapping and message queuing.
 */
class RedisService {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private val redisClient: RedisClient
    private val connection: StatefulRedisConnection<String, String>
    private val syncCommands: RedisCommands<String, String>

    init {
        val redisUri = RedisURI.Builder
            .redis(VoidConfig.Redis.host, VoidConfig.Redis.port)
            .apply {
                VoidConfig.Redis.password?.let { withPassword(it.toCharArray()) }
            }
            .build()

        redisClient = RedisClient.create(redisUri)
        connection = redisClient.connect()
        syncCommands = connection.sync()

        logger.info { "Redis connected to ${VoidConfig.Redis.host}:${VoidConfig.Redis.port}" }
    }

    /**
     * Store FCM token for an account.
     * PRIVACY: No IP addresses or other metadata stored.
     * If previous token exists, overwrite silently.
     */
    suspend fun storeFcmToken(accountId: String, fcmToken: String): Unit = withContext(Dispatchers.IO) {
        val key = "fcm:$accountId"
        syncCommands.setex(key, VoidConfig.Redis.tokenTtl, fcmToken)
        logger.info { "FCM token stored for account (TTL: ${VoidConfig.Redis.tokenTtl}s)" }
    }

    /**
     * Retrieve FCM token for an account.
     * Returns null if not found or expired.
     */
    suspend fun getFcmToken(accountId: String): String? = withContext(Dispatchers.IO) {
        val key = "fcm:$accountId"
        syncCommands.get(key)
    }

    /**
     * Store encrypted message in recipient's offline queue.
     * Messages are ephemeral and expire after TTL.
     */
    suspend fun queueMessage(message: EncryptedMessage): Unit = withContext(Dispatchers.IO) {
        val queueKey = "queue:${message.recipientId}"
        val messageJson = json.encodeToString(message)

        // Add to list (FIFO queue)
        syncCommands.lpush(queueKey, messageJson)

        // Set TTL on the queue
        syncCommands.expire(queueKey, Duration.ofSeconds(VoidConfig.Redis.messageTtl))

        logger.info { "Message queued (ID: ${message.messageId})" }
    }

    /**
     * Retrieve all pending messages for an account.
     * Returns messages in chronological order (oldest first).
     */
    suspend fun getPendingMessages(accountId: String): List<EncryptedMessage> = withContext(Dispatchers.IO) {
        val queueKey = "queue:$accountId"
        val messageJsonList = syncCommands.lrange(queueKey, 0, -1)

        messageJsonList.mapNotNull { messageJson ->
            try {
                json.decodeFromString<EncryptedMessage>(messageJson)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to decode message, skipping" }
                null
            }
        }.reversed() // Reverse to get chronological order (oldest first)
    }

    /**
     * Delete a message from the queue after successful delivery.
     * STRICT: We are a relay, not a cloud archive.
     */
    suspend fun deleteMessage(accountId: String, messageId: String): Unit = withContext(Dispatchers.IO) {
        val queueKey = "queue:$accountId"
        val messages = syncCommands.lrange(queueKey, 0, -1)

        // Find and remove the specific message
        messages.forEach { messageJson ->
            try {
                val message = json.decodeFromString<EncryptedMessage>(messageJson)
                if (message.messageId == messageId) {
                    syncCommands.lrem(queueKey, 1, messageJson)
                    logger.info { "Message deleted from queue (ID: $messageId)" }
                    return@withContext
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to decode message during deletion" }
            }
        }
    }

    /**
     * Delete all messages for an account (after full sync).
     */
    suspend fun clearQueue(accountId: String): Unit = withContext(Dispatchers.IO) {
        val queueKey = "queue:$accountId"
        syncCommands.del(queueKey)
        logger.info { "Queue cleared for account" }
    }

    /**
     * Get queue size for an account.
     */
    suspend fun getQueueSize(accountId: String): Long = withContext(Dispatchers.IO) {
        val queueKey = "queue:$accountId"
        syncCommands.llen(queueKey)
    }

    /**
     * Cleanup resources.
     */
    fun close() {
        connection.close()
        redisClient.shutdown()
        logger.info { "Redis connection closed" }
    }
}
