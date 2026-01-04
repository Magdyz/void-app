package com.void.app.service

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.void.block.identity.data.IdentityRepository
import com.void.block.messaging.sync.MessageSyncWorker
import com.void.slate.network.push.PushRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.android.ext.android.inject

/**
 * VoidFirebaseService - FCM receiver for payload-less "tickle" notifications.
 *
 * CRITICAL PRIVACY ARCHITECTURE:
 * - This service receives EMPTY notifications from Google
 * - NO message content passes through Google's servers
 * - The notification is just a "wake-up call" to check the Void server
 * - All actual message content is fetched via secure WebSocket and decrypted locally
 *
 * Flow:
 * 1. User A sends message to User B
 * 2. Void server stores encrypted blob for User B
 * 3. Void server sends empty FCM tickle to User B's device via Google
 * 4. This service wakes up and triggers MessageSyncWorker
 * 5. Worker connects to Void server, fetches encrypted blob, decrypts locally
 * 6. User B sees notification with decrypted content (Google never sees it)
 *
 * Token Registration:
 * - FCM generates a device token
 * - Token is sent to Void server (authenticated with Identity keys)
 * - Void server maps token to Account ID anonymously
 * - Server can now send wake-up tickles to this device
 */
class VoidFirebaseService : FirebaseMessagingService() {

    // Inject dependencies via Koin
    private val pushRegistration: PushRegistration by inject()
    private val identityRepository: IdentityRepository by inject()

    // Service-scoped coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "VoidFirebaseService"
    }

    /**
     * Called when a new FCM token is generated.
     * This happens on first install, or when token is rotated by Google.
     *
     * NOTE: This may be called BEFORE an identity is created during onboarding.
     * In that case, registration will be retried when:
     * 1. Identity is created (via IdentityCreated event in VoidApp)
     * 2. App restarts (via self-healing check in VoidApp)
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)

        Log.d(TAG, "🔑 FCM token refreshed by Google")
        Log.d(TAG, "Token (first 10 chars): ${token.take(10)}...")

        // Register token with Supabase server
        serviceScope.launch {
            try {
                // Get user's identity
                val identity = identityRepository.getIdentity()
                if (identity == null) {
                    Log.w(TAG, "⚠️  No identity found - cannot register push token yet")
                    Log.w(TAG, "   ✓ Will auto-register when identity is created")
                    Log.w(TAG, "   ✓ Or on next app start (self-healing)")
                    return@launch
                }

                // Register FCM token with current mailbox
                val result = pushRegistration.register(
                    identitySeed = identity.seed,
                    fcmToken = token
                )

                result.fold(
                    onSuccess = {
                        Log.d(TAG, "✅ FCM token registered after Google refresh")
                        Log.d(TAG, "   Server will send push notifications to this device")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "❌ FCM token registration failed: ${error.message}", error)
                        Log.e(TAG, "   Will retry on next app start (self-healing)")
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception during push registration: ${e.message}", e)
            }
        }
    }

    /**
     * Called when an FCM message is received.
     *
     * IMPORTANT: Do NOT process data here!
     * The message should be empty or just contain a "check_server" flag.
     * All actual message content is fetched securely via MessageSyncEngine.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Check if this is a heartbeat or message notification
        // Heartbeats have type="heartbeat", message notifications have no type field
        val messageType = remoteMessage.data["type"]
        val isHeartbeat = messageType == "heartbeat"

        if (isHeartbeat) {
            Log.d(TAG, "🫀 Heartbeat tick received (Poisson Ghost Protocol)")
            Log.d(TAG, "   Random interval sync - fetching mailbox")
        } else {
            Log.d(TAG, "⚡ Message notification received")
            Log.d(TAG, "   New message available - fetching mailbox")
        }

        // IMPORTANT: Both heartbeats and message notifications trigger mailbox fetch
        // The server always returns 2KB (real messages padded, or noise if empty)
        // This creates constant traffic that hides communication patterns from ISP/Google

        Log.d(TAG, "From: ${remoteMessage.from}")
        Log.d(TAG, "Data payload: ${remoteMessage.data}")

        // Wake up the sync engine using WorkManager
        // WorkManager guarantees execution even if app is killed
        // NOTE: Client ALWAYS fetches mailbox on tick (heartbeat or message)
        // Server ALWAYS returns 2KB (padded messages or noise)
        Log.d(TAG, "🚀 Triggering MessageSyncWorker")

        val syncRequest = OneTimeWorkRequest.Builder(MessageSyncWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)  // Fast delivery (Android 12+)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                MessageSyncWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,  // Don't duplicate if already running
                syncRequest
            )

        Log.d(TAG, "✅ MessageSyncWorker enqueued")
    }

    /**
     * Called when FCM message is deleted on the server.
     * This can happen if the device was offline for too long.
     */
    override fun onDeletedMessages() {
        super.onDeletedMessages()

        Log.w(TAG, "⚠️  Messages deleted (device was offline too long)")
        Log.d(TAG, "🔄 Performing full sync to recover")

        // Trigger a sync to fetch any missed messages
        val syncRequest = OneTimeWorkRequest.Builder(MessageSyncWorker::class.java)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                MessageSyncWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
    }
}
