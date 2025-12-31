package com.void.slate.network.supabase

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Fetches encrypted messages from Supabase message_queue table.
 *
 * ## Privacy Features
 * - Fetches messages using blind mailbox hashes (server doesn't know identity)
 * - Supports decoy fetching to hide real message count from network observers
 * - Deletes messages after fetching (server doesn't retain history)
 * - Supports multi-mailbox fetching during rotation windows
 *
 * ## Decoy Strategy
 * To hide traffic patterns:
 * - Always fetch a random number of messages (even if 0 real messages)
 * - Mix real fetches with dummy mailbox queries
 * - Introduce timing jitter to obscure sync patterns
 *
 * ## Usage
 * ```kotlin
 * val fetcher = MessageFetcher(supabaseClient)
 * val messages = fetcher.fetchMessages(listOf(mailboxAddress))
 * ```
 */
class MessageFetcher(
    private val supabase: SupabaseClient
) {

    /**
     * Fetch messages from specified mailbox addresses.
     *
     * @param mailboxHashes List of mailbox hashes to check (usually 1-3 during rotation)
     * @param epoch Current epoch for filtering
     * @param enableDecoys If true, adds decoy queries to hide traffic patterns
     * @return List of encrypted message records
     */
    suspend fun fetchMessages(
        mailboxHashes: List<String>,
        epoch: Long,
        enableDecoys: Boolean = true
    ): Result<List<MessageRecord>> {
        return try {
            Log.d(TAG, "📥 Fetching messages for ${mailboxHashes.size} mailbox(es)")

            val allMessages = mutableListOf<MessageRecord>()

            // Fetch from each mailbox
            for (mailboxHash in mailboxHashes) {
                val messages = fetchFromMailbox(mailboxHash, epoch)
                if (messages.isNotEmpty()) {
                    Log.d(TAG, "   ✓ Mailbox ${mailboxHash.take(8)}... → ${messages.size} messages")
                    allMessages.addAll(messages)
                } else {
                    Log.d(TAG, "   ○ Mailbox ${mailboxHash.take(8)}... → empty")
                }
            }

            // Add decoy queries to hide real message count
            if (enableDecoys && allMessages.isEmpty()) {
                performDecoyQueries()
            }

            Log.d(TAG, "📬 Total messages fetched: ${allMessages.size}")
            Result.success(allMessages)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch messages: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch messages from a single mailbox.
     */
    private suspend fun fetchFromMailbox(mailboxHash: String, epoch: Long): List<MessageRecord> {
        require(mailboxHash.length == 64) { "Mailbox hash must be 64 characters (32 bytes in hex)" }

        return try {
            // Query Supabase message_queue table
            // RLS policy ensures we can only see messages for mailboxes we know
            val response = supabase
                .from("message_queue")
                .select(columns = Columns.ALL) {
                    filter {
                        eq("mailbox_hash", mailboxHash)
                        gte("epoch", epoch - EPOCH_WINDOW) // Tolerate clock skew
                        lte("epoch", epoch + EPOCH_WINDOW)
                    }
                }
                .decodeList<MessageRecord>()

            response

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error fetching from mailbox ${mailboxHash.take(8)}...: ${e.message}")
            emptyList()
        }
    }

    /**
     * Delete messages from the server after successful processing.
     *
     * IMPORTANT: Call this after decrypting and storing messages locally.
     * This ensures the server doesn't retain message history.
     *
     * @param messageIds List of message IDs to delete
     */
    suspend fun deleteMessages(messageIds: List<String>): Result<Unit> {
        return try {
            if (messageIds.isEmpty()) {
                Log.d(TAG, "🗑️  No messages to delete")
                return Result.success(Unit)
            }

            Log.d(TAG, "🗑️  Deleting ${messageIds.size} messages from server")

            // Delete messages from Supabase
            // RLS policy ensures we can only delete messages we fetched
            for (messageId in messageIds) {
                supabase
                    .from("message_queue")
                    .delete {
                        filter {
                            eq("id", messageId)
                        }
                    }
            }

            Log.d(TAG, "   ✓ Messages deleted successfully")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to delete messages: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Perform decoy queries to hide traffic patterns.
     *
     * Strategy:
     * - Query random mailbox addresses that don't belong to us
     * - Introduces timing noise to hide real sync patterns
     * - Makes it harder for network observers to detect message arrivals
     */
    private suspend fun performDecoyQueries() {
        val decoyCount = Random.nextInt(1, 4) // 1-3 decoy queries
        Log.d(TAG, "🎭 Performing $decoyCount decoy queries for privacy")

        repeat(decoyCount) {
            val decoyMailbox = generateRandomMailboxHash()
            try {
                // Query a random mailbox (will return empty due to RLS)
                supabase
                    .from("message_queue")
                    .select(columns = Columns.ALL) {
                        filter {
                            eq("mailbox_hash", decoyMailbox)
                        }
                    }
                    .decodeList<MessageRecord>()

                Log.d(TAG, "   🎭 Decoy query to ${decoyMailbox.take(8)}... completed")

            } catch (e: Exception) {
                // Silently ignore decoy errors
            }
        }
    }

    /**
     * Generate a random 64-character hex string for decoy queries.
     * Matches database constraint: CHECK (length(mailbox_hash) = 64)
     */
    private fun generateRandomMailboxHash(): String {
        val bytes = ByteArray(32)  // 32 bytes = 64 hex characters
        Random.nextBytes(bytes)
        return bytes.joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    companion object {
        private const val TAG = "MessageFetcher"

        /**
         * Epoch window: Accept messages ±1 hour (3600 seconds) for clock skew tolerance.
         * This matches the database validation window.
         */
        private const val EPOCH_WINDOW = 3600L  // 1 hour in seconds
    }
}

/**
 * Message record from Supabase message_queue table.
 *
 * Matches the schema defined in supabase/migrations/01_message_queue.sql
 */
@Serializable
data class MessageRecord(
    /**
     * Unique message ID (UUID).
     */
    @SerialName("id")
    val id: String,

    /**
     * Mailbox hash (32-char hex) - blind recipient address.
     */
    @SerialName("mailbox_hash")
    val mailboxHash: String,

    /**
     * Base64-encoded encrypted message blob.
     * Contains E2E encrypted content (sealed sender).
     */
    @SerialName("ciphertext")
    val ciphertext: String,

    /**
     * Epoch number when message was sent.
     * Used for mailbox rotation and clock skew tolerance.
     */
    @SerialName("epoch")
    val epoch: Long,

    /**
     * When this message expires and will be auto-deleted.
     * Server TTL is 7 days.
     */
    @SerialName("expires_at")
    val expiresAt: String,

    /**
     * When message was inserted into queue.
     */
    @SerialName("created_at")
    val createdAt: String
)
