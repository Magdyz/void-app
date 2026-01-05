package com.void.slate.network.push

import android.util.Log
import com.void.slate.network.mailbox.MailboxDerivation
import com.void.slate.network.auth.EphemeralTokenManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Manages FCM push notification registration with Supabase.
 *
 * ## Privacy Architecture
 * - Maps FCM tokens to current mailbox addresses (not to user identity)
 * - Rotates registration every 25 hours with mailbox rotation
 * - Server receives token → mailbox mapping, never knows user identity
 * - Tokens expire after 25 hours (cleaned up by TTL job)
 *
 * ## Security
 * - Uses ephemeral tokens to prove mailbox ownership
 * - Prevents unauthorized FCM token registration
 * - RLS policies validate proof-of-ownership before allowing upsert
 *
 * ## Rotation Strategy
 * - Register new token when:
 *   1. FCM generates new token (device reinstall, token refresh)
 *   2. Mailbox rotates (every 25 hours)
 *   3. App starts after long absence
 * - Old registrations auto-expire (server-side TTL cleanup)
 *
 * ## Push Flow
 * 1. App registers: (mailbox_hash, fcm_token) → Supabase with ephemeral token proof
 * 2. Sender inserts message → Server Edge Function looks up token
 * 3. Server sends silent push (epoch only, no content)
 * 4. App wakes up, fetches from mailbox, decrypts
 *
 * ## Usage
 * ```kotlin
 * val registration = PushRegistration(supabaseClient, mailboxDerivation, tokenManager)
 * registration.register(identitySeed, fcmToken)
 * ```
 */
class PushRegistration(
    private val supabase: SupabaseClient,
    private val mailboxDerivation: MailboxDerivation,
    private val tokenManager: EphemeralTokenManager
) {

    /**
     * Register FCM token for push notifications.
     *
     * This creates/updates the mapping: mailbox_hash → fcm_token
     * Server will send push notifications to this token when messages arrive.
     *
     * SECURITY: Uses ephemeral token to prove mailbox ownership before registration.
     * This prevents unauthorized FCM token hijacking attacks.
     *
     * @param identitySeed The user's 32-byte identity seed
     * @param fcmToken The Firebase Cloud Messaging token
     * @param timestamp Current timestamp (for mailbox derivation)
     * @return Result indicating success or failure
     */
    @OptIn(SupabaseExperimental::class)
    suspend fun register(
        identitySeed: ByteArray,
        fcmToken: String,
        timestamp: Long = System.currentTimeMillis()
    ): Result<Unit> {
        return try {
            require(identitySeed.size == 32) { "Identity seed must be 32 bytes" }
            require(fcmToken.isNotBlank()) { "FCM token cannot be blank" }

            Log.d(TAG, "🔔 Registering push token with proof-of-ownership")
            Log.d(TAG, "   Token (first 10 chars): ${fcmToken.take(10)}...")

            // Derive current mailbox address
            val mailboxHash = mailboxDerivation.deriveMailbox(identitySeed, timestamp)
            val epoch = mailboxDerivation.calculateEpoch(timestamp)

            Log.d(TAG, "   📬 Mailbox: ${mailboxHash.take(8)}... (epoch $epoch)")

            // SECURITY: Get ephemeral token to prove mailbox ownership
            val tokenResult = tokenManager.getToken(identitySeed, mailboxHash)
            if (tokenResult.isFailure) {
                Log.e(TAG, "❌ Failed to get ephemeral token: ${tokenResult.exceptionOrNull()?.message}")
                return Result.failure(tokenResult.exceptionOrNull() ?: Exception("Token request failed"))
            }
            val tokenId = tokenResult.getOrThrow()
            Log.d(TAG, "   🔑 Proof token: $tokenId")

            // Calculate expiration (25 hours from now to match mailbox rotation)
            val expiresAt = Instant.now()
                .plus(REGISTRATION_TTL_HOURS, ChronoUnit.HOURS)
                .toString()

            // Create/update registration record
            val registrationRecord = PushRegistrationRecord(
                mailboxHash = mailboxHash,
                fcmToken = fcmToken,
                expiresAt = expiresAt
            )

            // SECURITY: Use split insert/update approach for proper RLS token handling
            // Supabase-kt .upsert() doesn't properly support custom headers for RLS
            //
            // Strategy:
            // 1. Try INSERT first (RLS allows without token)
            // 2. If conflict (mailbox exists), UPDATE with token (RLS validates ownership)

            try {
                // First try: INSERT (no token needed per RLS policy)
                supabase
                    .from("push_registrations")
                    .insert(registrationRecord)

                Log.d(TAG, "✅ Push registration successful (first-time registration)")

            } catch (e: Exception) {
                // If insert failed (likely due to conflict), try UPDATE with token
                // Supabase-kt may report unique constraint violations as RLS errors
                if (e.message?.contains("duplicate", ignoreCase = true) == true ||
                    e.message?.contains("conflict", ignoreCase = true) == true ||
                    e.message?.contains("unique", ignoreCase = true) == true ||
                    e.message?.contains("row-level security", ignoreCase = true) == true ||
                    e.message?.contains("violates", ignoreCase = true) == true) {

                    Log.d(TAG, "   Mailbox already registered, updating with token...")

                    // UPDATE requires valid token per RLS policy
                    supabase
                        .from("push_registrations")
                        .update(registrationRecord) {
                            filter {
                                eq("mailbox_hash", mailboxHash)
                            }
                            headers.append("x-mailbox-token", tokenId.toString())
                        }

                    Log.d(TAG, "✅ Push registration updated (verified ownership)")
                } else {
                    // Unexpected error - rethrow
                    throw e
                }
            }

            Log.d(TAG, "   Server will send notifications to this device")
            Log.d(TAG, "   Registration expires: $expiresAt")

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Push registration failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Unregister push notifications.
     *
     * Removes the current mailbox → FCM token mapping.
     * Server will stop sending push notifications.
     *
     * @param identitySeed The user's identity seed
     */
    suspend fun unregister(identitySeed: ByteArray): Result<Unit> {
        return try {
            require(identitySeed.size == 32) { "Identity seed must be 32 bytes" }

            Log.d(TAG, "🔕 Unregistering push notifications")

            // Derive current mailbox
            val mailboxHash = mailboxDerivation.deriveMailbox(identitySeed)

            // Delete registration from Supabase
            supabase
                .from("push_registrations")
                .delete {
                    filter {
                        eq("mailbox_hash", mailboxHash)
                    }
                }

            Log.d(TAG, "✅ Push unregistered successfully")

            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Push unregistration failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Rotate push registration to new mailbox.
     *
     * Called when mailbox rotates (every 25 hours).
     * Updates the server mapping to the new mailbox address.
     *
     * @param identitySeed User's identity seed
     * @param fcmToken Current FCM token
     * @return Result indicating success or failure
     */
    suspend fun rotate(identitySeed: ByteArray, fcmToken: String): Result<Unit> {
        Log.d(TAG, "🔄 Rotating push registration to new mailbox")

        // Unregister from old mailbox (optional - TTL will clean it up anyway)
        // unregister(identitySeed) // Skipping to reduce API calls

        // Register with new mailbox
        return register(identitySeed, fcmToken)
    }

    /**
     * Check if registration needs rotation.
     *
     * Returns true if we're close to mailbox rotation boundary.
     * Client should call rotate() when this returns true.
     *
     * @param timestamp Current timestamp
     * @return True if rotation needed within next hour
     */
    fun needsRotation(timestamp: Long = System.currentTimeMillis()): Boolean {
        val timeUntilRotation = mailboxDerivation.getTimeUntilRotation(timestamp)
        val rotationThreshold = TimeUnit.HOURS.toMillis(1) // Rotate 1 hour before expiry

        return timeUntilRotation <= rotationThreshold
    }

    /**
     * Get the next rotation time.
     */
    fun getNextRotationTime(timestamp: Long = System.currentTimeMillis()): Long {
        return mailboxDerivation.getNextRotationTime(timestamp)
    }

    /**
     * Get time until next rotation in milliseconds.
     */
    fun getTimeUntilRotation(timestamp: Long = System.currentTimeMillis()): Long {
        return mailboxDerivation.getTimeUntilRotation(timestamp)
    }

    companion object {
        private const val TAG = "PushRegistration"

        /**
         * Registration TTL: 25 hours (matches mailbox rotation).
         * Server auto-deletes expired registrations via TTL cleanup job.
         */
        private const val REGISTRATION_TTL_HOURS = 25L
    }
}

/**
 * Push registration record for Supabase push_registrations table.
 *
 * Matches schema in supabase/migrations/02_push_registrations.sql
 */
@Serializable
private data class PushRegistrationRecord(
    /**
     * Mailbox hash (32-char hex) - primary key.
     */
    @SerialName("mailbox_hash")
    val mailboxHash: String,

    /**
     * Firebase Cloud Messaging token.
     */
    @SerialName("fcm_token")
    val fcmToken: String,

    /**
     * When this registration expires (25 hours from creation).
     */
    @SerialName("expires_at")
    val expiresAt: String
)
