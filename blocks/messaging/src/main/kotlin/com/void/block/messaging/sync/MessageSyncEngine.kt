package com.void.block.messaging.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.void.block.messaging.crypto.MessageEncryptionService
import com.void.block.messaging.data.MessageRepository
import com.void.slate.network.NetworkClient
import com.void.slate.network.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * MessageSyncEngine - Core sync and notification engine for VOID
 *
 * Responsibilities:
 * - Maintain WebSocket connection to Void server
 * - Receive encrypted message blobs
 * - Decrypt messages locally
 * - Store messages in MessageRepository
 * - Post local notifications
 *
 * Modes:
 * - One-time sync: Connect, fetch messages, disconnect (for WorkManager)
 * - Persistent sync: Maintain 24/7 connection (for Hostile Mode)
 */
class MessageSyncEngine(
    private val context: Context,
    private val networkClient: NetworkClient,
    private val messageRepository: MessageRepository,
    private val encryptionService: MessageEncryptionService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "MessageSyncEngine"
        private const val NOTIFICATION_CHANNEL_ID = "void_messages"
        private const val NOTIFICATION_ID_BASE = 10000

        // Foreground service notification ID for Hostile Mode
        private const val FOREGROUND_NOTIFICATION_ID = 9999
    }

    private var persistentConnectionJob: Job? = null
    private var isForegroundService = false

    init {
        createNotificationChannel()
    }

    /**
     * Perform one-time sync - used by WorkManager when FCM tickle is received.
     * Connects, fetches pending messages, decrypts, and disconnects.
     */
    suspend fun performOneTimeSync(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "⚡ One-time sync triggered")

            // Connect to server
            networkClient.connect()

            // Fetch any pending messages
            val result = networkClient.receiveMessages()

            result.fold(
                onSuccess = { messages ->
                    Log.d(TAG, "📥 Received ${messages.size} messages")

                    messages.forEach { receivedMessage ->
                        try {
                            // Decrypt message locally
                            val decryptedContent = encryptionService.decryptMessage(
                                encryptedPayload = receivedMessage.encryptedPayload,
                                senderId = receivedMessage.senderIdentity
                            )

                            if (decryptedContent != null) {
                                // Create Message object
                                val message = com.void.block.messaging.domain.Message(
                                    id = receivedMessage.messageId,
                                    conversationId = receivedMessage.senderIdentity,
                                    senderId = receivedMessage.senderIdentity,
                                    recipientId = "me",
                                    content = com.void.block.messaging.domain.MessageContent.Text(decryptedContent),
                                    direction = com.void.block.messaging.domain.MessageDirection.INCOMING,
                                    timestamp = receivedMessage.serverTimestamp,
                                    status = com.void.block.messaging.domain.MessageStatus.DELIVERED,
                                    deliveredAt = receivedMessage.serverTimestamp,
                                    encryptedPayload = receivedMessage.encryptedPayload
                                )

                                // Store message in local database
                                messageRepository.receiveMessage(message)

                                // Post notification (generic for privacy)
                                postMessageNotification(receivedMessage.senderIdentity)
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to process message: ${e.message}", e)
                        }
                    }

                    Log.d(TAG, "✅ One-time sync completed successfully")
                },
                onFailure = { error ->
                    Log.e(TAG, "❌ Sync failed: ${error.message}", error)
                    return@withContext Result.failure(error)
                }
            )

            // Disconnect
            networkClient.disconnect()

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "❌ One-time sync exception: ${e.message}", e)
            networkClient.disconnect()
            Result.failure(e)
        }
    }

    /**
     * Start persistent connection - used for Hostile Mode.
     * Maintains 24/7 WebSocket connection for instant message delivery.
     * Requires foreground service to prevent Android from killing the connection.
     */
    fun startPersistentSync(asForegroundService: Boolean = false) {
        if (persistentConnectionJob?.isActive == true) {
            Log.d(TAG, "🔄 Persistent sync already running")
            return
        }

        isForegroundService = asForegroundService

        persistentConnectionJob = scope.launch {
            Log.d(TAG, "🚀 Starting persistent sync (Hostile Mode)")

            if (isForegroundService) {
                // Post persistent foreground notification
                postForegroundServiceNotification()
            }

            // Connect to server
            networkClient.connect()

            // Observe connection state
            launch {
                networkClient.observeConnectionState().collect { state ->
                    when (state) {
                        is ConnectionState.Connected -> {
                            Log.d(TAG, "🟢 WebSocket connected")
                        }
                        is ConnectionState.Disconnected -> {
                            Log.d(TAG, "🔴 WebSocket disconnected, attempting reconnect...")
                            delay(5000) // Wait 5 seconds before reconnecting
                            networkClient.connect()
                        }
                        is ConnectionState.Connecting -> {
                            Log.d(TAG, "🟡 WebSocket connecting...")
                        }
                        is ConnectionState.Error -> {
                            Log.e(TAG, "❌ WebSocket error: ${state.message}")
                            delay(5000) // Wait before retry
                            networkClient.connect()
                        }
                    }
                }
            }

            // Observe incoming messages in real-time
            networkClient.observeIncomingMessages().collect { receivedMessage ->
                try {
                    Log.d(TAG, "📨 Real-time message received from ${receivedMessage.senderIdentity}")

                    // Decrypt locally
                    val decryptedContent = encryptionService.decryptMessage(
                        encryptedPayload = receivedMessage.encryptedPayload,
                        senderId = receivedMessage.senderIdentity
                    )

                    if (decryptedContent != null) {
                        // Create Message object
                        val message = com.void.block.messaging.domain.Message(
                            id = receivedMessage.messageId,
                            conversationId = receivedMessage.senderIdentity,
                            senderId = receivedMessage.senderIdentity,
                            recipientId = "me",
                            content = com.void.block.messaging.domain.MessageContent.Text(decryptedContent),
                            direction = com.void.block.messaging.domain.MessageDirection.INCOMING,
                            timestamp = receivedMessage.serverTimestamp,
                            status = com.void.block.messaging.domain.MessageStatus.DELIVERED,
                            deliveredAt = receivedMessage.serverTimestamp,
                            encryptedPayload = receivedMessage.encryptedPayload
                        )

                        // Store in database
                        messageRepository.receiveMessage(message)

                        // Post notification
                        postMessageNotification(receivedMessage.senderIdentity)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to process real-time message: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Stop persistent connection.
     */
    suspend fun stopPersistentSync() {
        Log.d(TAG, "🛑 Stopping persistent sync")
        persistentConnectionJob?.cancelAndJoin()
        persistentConnectionJob = null
        networkClient.disconnect()

        if (isForegroundService) {
            removeForegroundServiceNotification()
        }
    }

    /**
     * Create notification channel for messages (required for Android O+).
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "VOID Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for new VOID messages"
            setShowBadge(true)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Post a generic notification when a new message arrives.
     * This is intentionally generic for privacy - no content/sender shown.
     */
    private fun postMessageNotification(senderId: String) {
        try {
            // Create intent to open the app
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Generic notification for privacy
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("VOID")
                .setContentText("Activity Detected")  // Intentionally vague for privacy
                .setSmallIcon(android.R.drawable.ic_dialog_email)  // TODO: Use app icon
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID_BASE + senderId.hashCode(), notification)

            Log.d(TAG, "📬 Notification posted")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to post notification: ${e.message}", e)
        }
    }

    /**
     * Post persistent foreground service notification (for Hostile Mode).
     * This keeps the app alive and WebSocket connection open.
     */
    private fun postForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VOID")
            .setContentText("Maintaining secure connection")
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // TODO: Use app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)

        Log.d(TAG, "📌 Foreground service notification posted")
    }

    /**
     * Remove foreground service notification.
     */
    private fun removeForegroundServiceNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(FOREGROUND_NOTIFICATION_ID)
    }

    /**
     * Enable Hostile Mode - promotes to foreground service.
     */
    fun enableHostileMode() {
        Log.d(TAG, "🚨 Hostile Mode enabled - promoting to foreground service")
        scope.launch {
            stopPersistentSync()
            startPersistentSync(asForegroundService = true)
        }
    }

    /**
     * Disable Hostile Mode - demotes from foreground service.
     */
    fun disableHostileMode() {
        Log.d(TAG, "✅ Hostile Mode disabled - returning to normal operation")
        scope.launch {
            stopPersistentSync()
        }
    }
}
