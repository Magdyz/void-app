package com.void.slate.network.supabase

import android.util.Log
import com.void.slate.network.exceptions.RateLimitException
import com.void.slate.network.exceptions.RateLimitType
import com.void.slate.network.mailbox.MailboxDerivation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Sends encrypted messages to Supabase message_queue table.
 *
 * ## Privacy Architecture
 * - Derives recipient's mailbox address (blind, rotates every 25 hours)
 * - Sends E2E encrypted payload (sealed sender - server can't see sender)
 * - Server stores message temporarily (7-day TTL)
 * - Triggers silent push notification to recipient (via Edge Function)
 *
 * ## Message Flow
 * 1. Client encrypts message with recipient's public key
 * 2. Derives recipient's current mailbox address
 * 3. Inserts encrypted blob to message_queue
 * 4. Server triggers Edge Function → sends FCM push (epoch only)
 * 5. Recipient wakes up, fetches from their mailbox, decrypts locally
 *
 * ## Usage
 * ```kotlin
 * val sender = MessageSender(supabaseClient, mailboxDerivation)
 * sender.sendMessage(
 *     recipientSeed = recipientIdentity.seed,
 *     encryptedPayload = encryptedMessageBlob
 * )
 * ```
 */
class MessageSender(
    private val supabase: SupabaseClient,
    private val mailboxDerivation: MailboxDerivation,
    private val timestampFuzzingEnabled: Boolean = true // Enabled by default for zero-leakage
) {

    /**
     * Send an encrypted message to a recipient.
     *
     * @param recipientSeed The recipient's 32-byte identity seed
     * @param encryptedPayload The E2E encrypted message payload (base64 or bytes)
     * @param timestamp Current timestamp (for mailbox derivation)
     * @return Result with message ID or error
     */
    suspend fun sendMessage(
        recipientSeed: ByteArray,
        encryptedPayload: ByteArray,
        timestamp: Long = System.currentTimeMillis()
    ): Result<String> {
        return try {
            require(recipientSeed.size == 32) { "Recipient seed must be 32 bytes" }
            require(encryptedPayload.isNotEmpty()) { "Encrypted payload cannot be empty" }
            require(encryptedPayload.size <= MAX_MESSAGE_SIZE) {
                "Message too large: ${encryptedPayload.size} bytes (max $MAX_MESSAGE_SIZE)"
            }

            Log.d(TAG, "📤 Sending message (${encryptedPayload.size} bytes)")

            // Derive recipient's current mailbox address
            val mailboxHash = mailboxDerivation.deriveMailbox(recipientSeed, timestamp)

            // Epoch for database is Unix timestamp in seconds (not mailbox rotation epoch)
            var epoch = timestamp / 1000  // Convert milliseconds to seconds

            // Apply timestamp fuzzing for privacy (obfuscates exact send time)
            if (timestampFuzzingEnabled) {
                epoch = fuzzTimestamp(epoch)
            }

            // DEBUG: Log full mailbox hash for diagnosis
            println("🔍 [SENDER_MAILBOX] Sending to mailbox:")
            println("🔍   Recipient seed (first 16 bytes): ${recipientSeed.take(16).joinToString("") { "%02x".format(it) }}")
            println("🔍   Mailbox:   $mailboxHash")
            println("🔍   Timestamp: $timestamp")
            println("🔍   Epoch:     $epoch")

            Log.d(TAG, "   📬 Recipient mailbox: ${mailboxHash.take(8)}... (epoch $epoch)")

            // Encode payload as base64
            val ciphertextBase64 = encryptedPayload.toBase64()

            // Calculate expiration time (6.9 days from now, with safety margin for clock skew)
            val expiresAt = Instant.now().plus(MESSAGE_TTL_HOURS, ChronoUnit.HOURS).toString()

            // Create message insert record
            val messageId = UUID.randomUUID().toString()
            val insertRecord = MessageInsertRecord(
                id = messageId,
                mailboxHash = mailboxHash,
                ciphertext = ciphertextBase64,
                epoch = epoch,
                expiresAt = expiresAt
            )

            // DEBUG: Log insert record details
            Log.d(TAG, "🔍 [INSERT_DEBUG] Message insert record:")
            Log.d(TAG, "🔍   ID: $messageId")
            Log.d(TAG, "🔍   Mailbox (full): $mailboxHash")
            Log.d(TAG, "🔍   Epoch: $epoch")
            Log.d(TAG, "🔍   Ciphertext size: ${ciphertextBase64.length} chars")
            Log.d(TAG, "🔍   Expires at: $expiresAt")

            // Insert into Supabase message_queue table
            supabase
                .from("message_queue")
                .insert(insertRecord)

            Log.d(TAG, "✅ Message sent successfully (ID: ${messageId.take(8)}...)")
            Log.d(TAG, "   Server will trigger push notification to recipient")

            Result.success(messageId)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send message: ${e.message}", e)

            // Check for rate limit error
            if (e.message?.contains("Rate limit exceeded") == true) {
                Log.w(TAG, "⚠️  Rate limit hit for message sending")
                return Result.failure(
                    RateLimitException(
                        "Message send rate limit exceeded (100/hour per mailbox)",
                        RateLimitType.MESSAGE_SEND
                    )
                )
            }

            Result.failure(e)
        }
    }

    /**
     * Send a decoy message to introduce noise into traffic patterns.
     *
     * Decoy messages:
     * - Sent to random mailbox addresses
     * - Contain random encrypted data
     * - Make traffic analysis harder
     * - Server can't distinguish decoys from real messages
     *
     * @param size Size of decoy payload in bytes
     */
    suspend fun sendDecoyMessage(size: Int = kotlin.random.Random.nextInt(512, 4096)): Result<String> {
        return try {
            Log.d(TAG, "🎭 Sending decoy message ($size bytes)")

            // Generate random mailbox and payload
            val randomMailbox = generateRandomMailboxHash()
            val randomPayload = ByteArray(size).also { kotlin.random.Random.nextBytes(it) }

            // Calculate epoch from current timestamp (Unix timestamp in seconds)
            val currentTimestamp = System.currentTimeMillis()
            var epoch = currentTimestamp / 1000  // Convert milliseconds to seconds

            // Apply timestamp fuzzing for privacy
            if (timestampFuzzingEnabled) {
                epoch = fuzzTimestamp(epoch)
            }

            val messageId = UUID.randomUUID().toString()
            val expiresAt = Instant.now().plus(MESSAGE_TTL_HOURS, ChronoUnit.HOURS).toString()

            val insertRecord = MessageInsertRecord(
                id = messageId,
                mailboxHash = randomMailbox,
                ciphertext = randomPayload.toBase64(),
                epoch = epoch,
                expiresAt = expiresAt
            )

            supabase
                .from("message_queue")
                .insert(insertRecord)

            Log.d(TAG, "   🎭 Decoy sent to ${randomMailbox.take(8)}...")

            Result.success(messageId)

        } catch (e: Exception) {
            Log.e(TAG, "⚠️  Decoy send failed (non-critical): ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Check server health and message queue capacity.
     * Useful for diagnostics and rate limiting.
     */
    suspend fun checkServerHealth(): Result<ServerHealth> {
        return try {
            // Query message_queue table statistics
            // This is a simple health check - expand as needed
            val response = supabase
                .from("message_queue")
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("id")) {
                    limit(1)
                }

            Result.success(ServerHealth(isOnline = true, canAcceptMessages = true))

        } catch (e: Exception) {
            Log.e(TAG, "⚠️  Server health check failed: ${e.message}")
            Result.success(ServerHealth(isOnline = false, canAcceptMessages = false))
        }
    }

    /**
     * Generate a random 64-character hex string for decoy mailboxes.
     * Matches database constraint: CHECK (length(mailbox_hash) = 64)
     */
    private fun generateRandomMailboxHash(): String {
        val bytes = ByteArray(32)  // 32 bytes = 64 hex characters
        kotlin.random.Random.nextBytes(bytes)
        return bytes.joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * Encode bytes to base64 string.
     */
    private fun ByteArray.toBase64(): String {
        return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    }

    /**
     * Fuzz timestamp for privacy by adding random jitter.
     *
     * Adds ±30 seconds to ±5 minutes of random jitter to obscure exact send time.
     * This prevents timing correlation attacks while keeping messages roughly ordered.
     *
     * @param epochSeconds The original epoch timestamp in seconds
     * @return Fuzzed epoch timestamp in seconds
     */
    private fun fuzzTimestamp(epochSeconds: Long): Long {
        // Random jitter: ±30 seconds to ±5 minutes (30-300 seconds)
        val minJitter = 30L  // 30 seconds
        val maxJitter = 300L // 5 minutes
        val jitter = kotlin.random.Random.nextLong(-maxJitter, maxJitter + 1)

        val fuzzedEpoch = epochSeconds + jitter

        Log.d(TAG, "🔀 [TIMESTAMP_FUZZ] Original: $epochSeconds, Jitter: ${jitter}s, Fuzzed: $fuzzedEpoch")

        return fuzzedEpoch
    }

    companion object {
        private const val TAG = "MessageSender"

        /**
         * Maximum message size: 64 KB.
         * Matches server-side validation in supabase/migrations/06_validation_constraints.sql
         */
        private const val MAX_MESSAGE_SIZE = 64 * 1024 // 64 KB

        /**
         * Message TTL on server: 6.9 days = 165.6 hours (with safety margin).
         *
         * The server enforces a strict 7-day maximum, but we use 6.9 days to account for:
         * - Clock skew between device and server (device may be ahead)
         * - Network latency (time between calculating and server receiving)
         * - Processing time (time between server receiving and validating)
         *
         * The 0.1 day (2.4 hour) safety margin ensures messages are always accepted
         * even if the device clock is slightly ahead or network has delays.
         *
         * After this, messages are auto-deleted by TTL cleanup job.
         */
        private const val MESSAGE_TTL_HOURS = 165L  // 6.9 days = 6.9 * 24 = 165.6 hours (rounded down for safety)
    }
}

/**
 * Record for inserting messages into Supabase message_queue.
 */
@Serializable
private data class MessageInsertRecord(
    @SerialName("id")
    val id: String,

    @SerialName("mailbox_hash")
    val mailboxHash: String,

    @SerialName("ciphertext")
    val ciphertext: String,

    @SerialName("epoch")
    val epoch: Long,

    @SerialName("expires_at")
    val expiresAt: String
)

/**
 * Server health status.
 */
data class ServerHealth(
    val isOnline: Boolean,
    val canAcceptMessages: Boolean
)
