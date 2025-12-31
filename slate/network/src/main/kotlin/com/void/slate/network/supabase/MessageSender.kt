package com.void.slate.network.supabase

import android.util.Log
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
    private val mailboxDerivation: MailboxDerivation
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
            val epoch = timestamp / 1000  // Convert milliseconds to seconds

            Log.d(TAG, "   📬 Recipient mailbox: ${mailboxHash.take(8)}... (epoch $epoch)")

            // Encode payload as base64
            val ciphertextBase64 = encryptedPayload.toBase64()

            // Calculate expiration time (7 days from now)
            val expiresAt = Instant.now().plus(MESSAGE_TTL_DAYS, ChronoUnit.DAYS).toString()

            // Create message insert record
            val messageId = UUID.randomUUID().toString()
            val insertRecord = MessageInsertRecord(
                id = messageId,
                mailboxHash = mailboxHash,
                ciphertext = ciphertextBase64,
                epoch = epoch,
                expiresAt = expiresAt
            )

            // Insert into Supabase message_queue table
            supabase
                .from("message_queue")
                .insert(insertRecord)

            Log.d(TAG, "✅ Message sent successfully (ID: ${messageId.take(8)}...)")
            Log.d(TAG, "   Server will trigger push notification to recipient")

            Result.success(messageId)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send message: ${e.message}", e)
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
            val epoch = mailboxDerivation.calculateEpoch()

            val messageId = UUID.randomUUID().toString()
            val expiresAt = Instant.now().plus(MESSAGE_TTL_DAYS, ChronoUnit.DAYS).toString()

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

    companion object {
        private const val TAG = "MessageSender"

        /**
         * Maximum message size: 64 KB.
         * Matches server-side validation in supabase/migrations/06_validation_constraints.sql
         */
        private const val MAX_MESSAGE_SIZE = 64 * 1024 // 64 KB

        /**
         * Message TTL on server: 7 days.
         * After this, messages are auto-deleted by TTL cleanup job.
         */
        private const val MESSAGE_TTL_DAYS = 7L
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
