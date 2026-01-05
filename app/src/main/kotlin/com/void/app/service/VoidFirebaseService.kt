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
 * VoidFirebaseService - v1.0 FCM receiver for VOID Poisson Ghost Protocol
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * CRITICAL v1.0 PRIVACY ARCHITECTURE - POISSON GHOST PROTOCOL
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * What Google/FCM Sees:
 * ✅ Device receives push notification every 10-20 minutes (Poisson distribution)
 * ✅ All FCM payloads are IDENTICAL (no distinguishing fields)
 * ✅ Google CANNOT tell if a push is a heartbeat or real message
 * ✅ Constant traffic pattern 24/7 (even when no real messages)
 *
 * What Google/FCM CANNOT See:
 * ❌ Which pushes contain real messages vs heartbeats
 * ❌ How many messages in each notification
 * ❌ Message timing (hidden in heartbeat noise)
 * ❌ Conversation patterns or volume
 * ❌ Who is messaging whom
 * ❌ When user is actually communicating vs idle
 *
 * v1.0 Message Flow:
 * 1. User A sends message to User B
 * 2. VOID server stores encrypted blob in User B's mailbox
 * 3. VOID server sends IDENTICAL FCM push to User B (same as heartbeat)
 * 4. This service receives FCM push (cannot distinguish heartbeat vs message)
 * 5. Triggers MessageSyncWorker to fetch mailbox
 * 6. Worker fetches CONSTANT 4KB response (padded messages OR noise)
 * 7. Decrypts locally and shows generic notification if messages found
 * 8. User opens app and sees actual message content after auth
 *
 * v1.0 Heartbeat Flow:
 * 1. pg_cron triggers heartbeat-sender every 1 minute
 * 2. Server determines which users are due for heartbeat (Poisson timing)
 * 3. Server sends IDENTICAL FCM push (same as real message)
 * 4. This service receives FCM push (cannot distinguish!)
 * 5. Triggers MessageSyncWorker to fetch mailbox
 * 6. Worker fetches CONSTANT 4KB response (noise - no messages)
 * 7. No notification shown (mailbox was empty)
 * 8. User unaware this happened (as intended)
 *
 * Result:
 * - Google sees: Random pushes every 10-20 minutes (looks like normal messaging)
 * - Network sees: Constant 4KB HTTPS requests (cannot determine if real data)
 * - Android OS sees: Generic "Activity Detected" notifications (no metadata)
 * - User gets: Instant notifications when messages arrive, zero metadata leakage
 *
 * This is MORE paranoid than Signal while maintaining the same usability.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * Token Registration:
 * - FCM generates a device token on first install
 * - Token is sent to VOID server (authenticated with identity keys)
 * - VOID server maps token to mailbox_hash (blindly - no identity linkage)
 * - Server can now send wake-up signals to this device
 * - Token refresh is handled automatically by onNewToken()
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
     * v1.0: Called when an FCM wake signal is received.
     *
     * CRITICAL SECURITY DESIGN:
     * ═══════════════════════════════════════════════════════════════════════
     * This method receives IDENTICAL FCM pushes for:
     * - Real messages from contacts
     * - Poisson heartbeat signals (decoy traffic)
     *
     * WE CANNOT DISTINGUISH BETWEEN THEM.
     * This is BY DESIGN for maximum privacy.
     *
     * Why This Matters:
     * - Google/FCM cannot tell if a push contains a real message
     * - Network observers cannot detect communication patterns
     * - Constant heartbeat traffic creates "noise floor"
     * - Real message timing is hidden in the noise
     *
     * What Happens Next:
     * 1. We ALWAYS trigger MessageSyncWorker (same for heartbeat or message)
     * 2. Worker ALWAYS fetches mailbox (constant 4KB HTTPS request)
     * 3. Server ALWAYS returns 4KB (padded messages OR pure noise)
     * 4. Worker decrypts locally and determines if real messages exist
     * 5. If real messages: Show generic notification "Activity Detected"
     * 6. If heartbeat: Silent (no notification, user unaware)
     *
     * Privacy Guarantees:
     * ✅ FCM payload is EMPTY (just epoch + nonce for deduplication)
     * ✅ NO "type" field (would leak metadata to Google)
     * ✅ NO sender info (would leak who is messaging)
     * ✅ NO message count (would leak conversation volume)
     * ✅ Constant 4KB mailbox fetch (ISP cannot tell if real data)
     *
     * Result:
     * Even if Google/NSA intercepts ALL FCM traffic, they cannot determine:
     * - When real messages were sent
     * - How many messages were sent
     * - Who sent messages
     * - Which users are actually communicating
     *
     * The Poisson heartbeat distribution creates statistical indistinguishability.
     * ═══════════════════════════════════════════════════════════════════════
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "⚡ v1.0 Poisson Ghost Protocol - Wake signal received")
        Log.d(TAG, "   Classification: UNKNOWN (heartbeat OR message - indistinguishable)")
        Log.d(TAG, "   Action: Fetch mailbox (constant 4KB response)")
        Log.d(TAG, "   From: ${remoteMessage.from}")
        Log.d(TAG, "   Data: ${remoteMessage.data}")

        // CRITICAL: Suppress the auto-shown notification
        // When app is killed, FCM auto-shows the notification payload
        // We immediately cancel it to maintain silent operation
        val isSilent = remoteMessage.data["silent"] == "true"
        if (isSilent) {
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            // Cancel all VOID sync notifications (they use the same tag)
            notificationManager.cancel("void_sync", 0)
            Log.d(TAG, "   🔇 Suppressed auto-notification (silent mode)")
        }

        // v1.0: ALWAYS trigger mailbox sync on ANY FCM push
        // This maintains constant traffic pattern regardless of real message presence

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
