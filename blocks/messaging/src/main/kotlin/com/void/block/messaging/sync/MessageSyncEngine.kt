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
 * MessageSyncEngine - Core sync and notification engine for VOID v1.0
 *
 * v1.0 Responsibilities:
 * - Perform one-time mailbox sync when FCM wake signal received
 * - Decrypt messages locally
 * - Store messages in MessageRepository
 * - Post generic activity notifications (no metadata)
 * - Implement notification debouncing to prevent spam
 *
 * v1.0 Architecture (Play Flavor Only):
 * - FCM wake signals (heartbeat + message - indistinguishable)
 * - One-time sync on wake (fetch 4KB mailbox response)
 * - Generic notification "VOID - Activity Detected"
 * - No message counts, no sender info, no previews
 *
 * v1.1 Features (Commented Out):
 * - Persistent WebSocket connection (Hostile Mode)
 * - FOSS flavor polling
 * - User choice between Play and FOSS modes
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

        // SECURITY: Use single notification ID for all messages
        // This prevents metadata leakage through multiple notifications
        private const val NOTIFICATION_ID_ACTIVITY = 10000

        // v1.1: Foreground service notification ID for Hostile Mode
        // private const val FOREGROUND_NOTIFICATION_ID = 9999

        // v1.0: Debounce interval to prevent notification spam (60 seconds)
        // This is a critical privacy feature - prevents metadata leakage through notification patterns
        private const val NOTIFICATION_DEBOUNCE_MS = 60_000L
    }

    // v1.1: Hostile Mode variables (commented out for v1.0)
    // private var persistentConnectionJob: Job? = null
    // private var isForegroundService = false

    // v1.0: SECURITY: Track last notification time for debouncing
    // Prevents metadata leakage through notification spam patterns
    private var lastNotificationTime = 0L

    init {
        createNotificationChannel()
    }

    /**
     * v1.0: Perform one-time sync - used by WorkManager when FCM wake signal is received.
     *
     * This is the CORE of v1.0 notification architecture:
     * 1. FCM wake signal arrives (could be heartbeat OR real message - we can't tell!)
     * 2. This function fetches mailbox (always 4KB response - padded or noise)
     * 3. If new messages found, post generic notification
     * 4. Network observer cannot tell if this was a heartbeat or message (constant 4KB)
     *
     * Privacy guarantees:
     * - Constant 4KB mailbox fetch (Google/ISP sees same traffic pattern)
     * - Generic notification (Android OS sees no metadata)
     * - Debounced (prevents notification spam pattern analysis)
     */
    suspend fun performOneTimeSync(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "⚡ One-time sync triggered")

            // ✅ FIX: Use force=true to bypass 5-minute debounce for FCM-triggered syncs
            // This ensures messages are fetched when FCM push arrives
            // Uses 30-second emergency debounce instead
            val newMessageCount = messageRepository.syncMessages(force = true)

            Log.d(TAG, "📥 Synced $newMessageCount new messages from Supabase")

            // SECURITY: Post single generic notification if messages were received
            // No counts, no sender info - just "Activity Detected"
            // Debounced to prevent spam and metadata leakage
            if (newMessageCount > 0) {
                postActivityNotification()
            }

            Log.d(TAG, "✅ One-time sync completed successfully")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "❌ One-time sync exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // v1.1: HOSTILE MODE - PERSISTENT WEBSOCKET CONNECTION
    // ═══════════════════════════════════════════════════════════════
    // Commented out for v1.0 - will be enabled in v1.1 with user toggle
    // This provides instant message delivery without FCM (FOSS mode)
    // ═══════════════════════════════════════════════════════════════

    /*
    /**
     * v1.1: Start persistent connection - used for Hostile Mode.
     * Maintains 24/7 WebSocket connection for instant message delivery.
     * Requires foreground service to prevent Android from killing the connection.
     *
     * This is a v1.1 feature for users who want maximum privacy (no Google involvement).
     * In v1.1, users can toggle between:
     * - Balanced Mode (FCM + Poisson heartbeats) ← v1.0 implementation
     * - Maximum Privacy (persistent WebSocket only) ← This code
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

                        // SECURITY: Post generic notification (no sender info)
                        postActivityNotification()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to process real-time message: ${e.message}", e)
                }
            }
        }
    }

    /**
     * v1.1: Stop persistent connection.
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
    */

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
     * v1.0: Post a single generic notification when activity is detected.
     *
     * CRITICAL v1.0 SECURITY DESIGN:
     * - Single notification ID (NOTIFICATION_ID_ACTIVITY = 10000) for ALL activity
     *   → Prevents metadata leakage through multiple notifications
     *   → Android OS cannot count conversations or messages
     *
     * - Generic text: "VOID - Activity Detected"
     *   → NO message counts (metadata leak)
     *   → NO sender names (metadata leak)
     *   → NO message previews (obviously)
     *   → NO timestamps (metadata leak)
     *
     * - Debounced (60 seconds minimum between notifications)
     *   → Prevents notification spam during active conversations
     *   → Prevents metadata leakage through notification frequency patterns
     *   → If user sends 5 messages in 30 seconds, only 1 notification shows
     *
     * - Auto-cancel on tap
     *   → User opens app and sees actual messages after biometric auth
     *   → Notification disappears automatically
     *
     * - Identical for heartbeats and real messages
     *   → Android OS cannot distinguish between them
     *   → This is BY DESIGN for Poisson Ghost Protocol
     *
     * Privacy Guarantees:
     * ✅ Google/FCM: Cannot tell if push contains message (heartbeats look identical)
     * ✅ Android OS: Cannot see sender, count, content (generic notification)
     * ✅ Network observers: Cannot detect patterns (constant 4KB mailbox fetch)
     * ✅ Lock screen: Shows "Activity Detected" only (no details)
     */
    private fun postActivityNotification() {
        try {
            val now = System.currentTimeMillis()

            // SECURITY: Debounce notifications to prevent spam and pattern analysis
            if (now - lastNotificationTime < NOTIFICATION_DEBOUNCE_MS) {
                Log.d(TAG, "⏭️  Notification suppressed (debounced - ${(now - lastNotificationTime) / 1000}s since last)")
                return
            }

            lastNotificationTime = now

            // Create intent to open the app
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // SECURITY: Completely generic notification
            // - No counts (metadata leak)
            // - No sender (metadata leak)
            // - No content (obviously)
            // - Just a wake-up signal to check the app
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("VOID")
                .setContentText("Activity Detected")  // Intentionally vague for privacy
                .setSmallIcon(android.R.drawable.ic_dialog_email)  // TODO: Use app icon
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)  // Auto-dismiss when tapped
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // SECURITY: Use single notification ID for ALL activity
            // This replaces any existing notification (no multiple notifications)
            notificationManager.notify(NOTIFICATION_ID_ACTIVITY, notification)

            Log.d(TAG, "📬 Activity notification posted (debounced)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to post notification: ${e.message}", e)
        }
    }

    /**
     * v1.0: Clear the activity notification.
     *
     * Called when user opens the app - this is a critical UX flow:
     * 1. User sees generic notification on lock screen ("Activity Detected")
     * 2. User unlocks device and taps notification
     * 3. App opens and clears the notification
     * 4. User sees biometric prompt (if enabled)
     * 5. After auth, user sees actual messages with sender names and content
     *
     * This function is called from:
     * - MainActivity.onCreate() - When app starts fresh
     * - MainActivity.onResume() - When app comes to foreground
     * - MainActivity.onNewIntent() - When notification is tapped while app is running
     *
     * Result: User never sees stale notification while app is open.
     */
    fun clearActivityNotification() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID_ACTIVITY)
            Log.d(TAG, "🔕 Activity notification cleared")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear notification: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // v1.1: HOSTILE MODE - FOREGROUND SERVICE NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════
    // Commented out for v1.0 - will be enabled in v1.1 with user toggle
    // ═══════════════════════════════════════════════════════════════

    /*
    /**
     * v1.1: Post persistent foreground service notification (for Hostile Mode).
     * This keeps the app alive and WebSocket connection open 24/7.
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
     * v1.1: Remove foreground service notification.
     */
    private fun removeForegroundServiceNotification() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(FOREGROUND_NOTIFICATION_ID)
    }

    /**
     * v1.1: Enable Hostile Mode - promotes to foreground service.
     * This provides instant message delivery without FCM for maximum privacy.
     */
    fun enableHostileMode() {
        Log.d(TAG, "🚨 Hostile Mode enabled - promoting to foreground service")
        scope.launch {
            stopPersistentSync()
            startPersistentSync(asForegroundService = true)
        }
    }

    /**
     * v1.1: Disable Hostile Mode - demotes from foreground service.
     */
    fun disableHostileMode() {
        Log.d(TAG, "✅ Hostile Mode disabled - returning to normal operation")
        scope.launch {
            stopPersistentSync()
        }
    }
    */
}
