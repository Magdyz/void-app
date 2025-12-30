package com.void.app.service

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.void.block.messaging.sync.MessageSyncWorker

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

    companion object {
        private const val TAG = "VoidFirebaseService"
    }

    /**
     * Called when a new FCM token is generated.
     * This happens on first install, or when token is rotated by Google.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)

        Log.d(TAG, "🔑 New FCM token generated")
        Log.d(TAG, "Token (first 10 chars): ${token.take(10)}...")

        // TODO: Send this token to Void server securely
        // - Authenticate using Identity keys
        // - Server stores token mapped to Account ID
        // - Server can now send wake-up tickles to this device
        //
        // Example API call:
        // POST /api/v1/register-push-token
        // Headers: X-Account-ID: <identity>, X-Signature: <signed request>
        // Body: { "fcm_token": "<token>", "platform": "android" }

        Log.d(TAG, "⚠️  TODO: Implement token registration with Void server")
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

        Log.d(TAG, "⚡ Wake-up tickle received from Google")
        Log.d(TAG, "From: ${remoteMessage.from}")
        Log.d(TAG, "Data payload: ${remoteMessage.data}")

        // Verify the notification is genuinely empty (privacy check)
        if (remoteMessage.data.isNotEmpty() && remoteMessage.data.size > 1) {
            Log.w(TAG, "⚠️  WARNING: Received non-empty FCM payload!")
            Log.w(TAG, "This violates Void's privacy architecture!")
            Log.w(TAG, "Expected: Empty tickle. Got: ${remoteMessage.data}")
        }

        // Wake up the sync engine using WorkManager
        // WorkManager guarantees execution even if app is killed
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
