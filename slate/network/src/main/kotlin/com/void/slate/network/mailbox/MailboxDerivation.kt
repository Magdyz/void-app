package com.void.slate.network.mailbox

import com.void.slate.crypto.CryptoProvider
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Derives blind mailbox addresses for anonymous message delivery.
 *
 * ## Privacy Architecture
 * - Mailboxes are derived from user's identity seed using time-based epochs
 * - Server sees only opaque mailbox hashes, never the actual identity
 * - Mailboxes rotate every 25 hours for forward secrecy
 * - Sender and recipient independently derive the same mailbox address
 *
 * ## Mailbox Format
 * - 64-character hex string (32 bytes)
 * - Example: `a3f5c2e1b4d8f9a7c3e5d2f1b8a4c6e9a3f5c2e1b4d8f9a7c3e5d2f1b8a4c6e9`
 *
 * ## Rotation Schedule
 * - Epoch 0: 1970-01-01 00:00:00 UTC
 * - Epoch N: Current time / 25 hours
 * - New mailbox every 25 hours
 * - Old mailboxes expire after 7 days (server TTL)
 *
 * ## Usage
 * ```kotlin
 * val derivation = MailboxDerivation(crypto)
 * val currentMailbox = derivation.deriveMailbox(identitySeed, System.currentTimeMillis())
 * ```
 */
class MailboxDerivation(
    private val crypto: CryptoProvider
) {

    /**
     * Derive the current mailbox address for a given identity.
     *
     * @param identitySeed The user's 32-byte identity seed
     * @param timestamp Current timestamp in milliseconds (for epoch calculation)
     * @return 32-character hex mailbox hash
     */
    suspend fun deriveMailbox(identitySeed: ByteArray, timestamp: Long = System.currentTimeMillis()): String {
        require(identitySeed.size == 32) { "Identity seed must be 32 bytes" }

        val epoch = calculateEpoch(timestamp)
        android.util.Log.d("MailboxDerivation", "🕒 [EPOCH_CALC] timestamp=$timestamp ms → epoch=$epoch (duration=${EPOCH_DURATION_MS}ms)")
        return deriveMailboxForEpoch(identitySeed, epoch)
    }

    /**
     * Derive mailbox address for a specific epoch.
     * Useful for checking future/past mailboxes during rotation windows.
     *
     * @param identitySeed The user's 32-byte identity seed
     * @param epoch The epoch number (timestamp / 25 hours)
     * @return 32-character hex mailbox hash
     */
    suspend fun deriveMailboxForEpoch(identitySeed: ByteArray, epoch: Long): String {
        require(identitySeed.size == 32) { "Identity seed must be 32 bytes" }

        // DEBUG: Log full derivation inputs
        val seedHex = identitySeed.joinToString("") { "%02x".format(it) }
        android.util.Log.d("MailboxDerivation", "🔍 [MAILBOX_DERIVE]")
        android.util.Log.d("MailboxDerivation", "🔍   Identity Seed (full): $seedHex")
        android.util.Log.d("MailboxDerivation", "🔍   Epoch: $epoch")

        // Derive mailbox using KDF: HMAC-SHA256(seed, "mailbox/epoch/{epoch}")
        val derivationPath = "mailbox/epoch/$epoch"
        val derived = crypto.derive(identitySeed, derivationPath)

        android.util.Log.d("MailboxDerivation", "🔍   Derivation path: $derivationPath")
        android.util.Log.d("MailboxDerivation", "🔍   Derived KDF output: ${derived.joinToString("") { "%02x".format(it) }}")

        // Hash to 16 bytes for mailbox address
        val mailboxBytes = hashTo16Bytes(derived)

        // Convert to 32-character hex string
        val mailboxHash = mailboxBytes.toHexString()
        android.util.Log.d("MailboxDerivation", "🔍   Final Mailbox Hash: $mailboxHash")

        return mailboxHash
    }

    /**
     * Get all active mailbox addresses for the current rotation window.
     *
     * During rotation transitions (1 hour before/after epoch boundary),
     * the client should check multiple mailboxes:
     * - Previous epoch (for messages sent just before rotation)
     * - Current epoch (primary mailbox)
     * - Next epoch (for clock skew tolerance)
     *
     * @param identitySeed The user's 32-byte identity seed
     * @param timestamp Current timestamp in milliseconds
     * @return List of mailbox addresses to check (1-3 mailboxes)
     */
    suspend fun getActiveMailboxes(
        identitySeed: ByteArray,
        timestamp: Long = System.currentTimeMillis()
    ): List<MailboxAddress> {
        val currentEpoch = calculateEpoch(timestamp)
        val epochProgress = getEpochProgress(timestamp)

        val mailboxes = mutableListOf<MailboxAddress>()

        // Check previous epoch during first hour (late arrivals from old epoch)
        if (epochProgress < ROTATION_WINDOW_START_MS) {
            mailboxes.add(
                MailboxAddress(
                    hash = deriveMailboxForEpoch(identitySeed, currentEpoch - 1),
                    epoch = currentEpoch - 1,
                    isPrimary = false
                )
            )
        }

        // Always check current epoch (primary)
        mailboxes.add(
            MailboxAddress(
                hash = deriveMailboxForEpoch(identitySeed, currentEpoch),
                epoch = currentEpoch,
                isPrimary = true
            )
        )

        // Check next epoch during last hour (early senders with clock skew)
        if (epochProgress > ROTATION_WINDOW_END_MS) {
            mailboxes.add(
                MailboxAddress(
                    hash = deriveMailboxForEpoch(identitySeed, currentEpoch + 1),
                    epoch = currentEpoch + 1,
                    isPrimary = false
                )
            )
        }

        return mailboxes
    }

    /**
     * Calculate the current epoch number from a timestamp.
     *
     * Epoch = floor(timestamp / 25 hours)
     */
    fun calculateEpoch(timestamp: Long = System.currentTimeMillis()): Long {
        return timestamp / EPOCH_DURATION_MS
    }

    /**
     * Get progress within current epoch (0 to EPOCH_DURATION_MS).
     * Useful for determining when to rotate mailboxes.
     */
    fun getEpochProgress(timestamp: Long = System.currentTimeMillis()): Long {
        return timestamp % EPOCH_DURATION_MS
    }

    /**
     * Check if we're currently in a rotation window.
     * Rotation windows occur:
     * - First hour of new epoch (accepting late messages)
     * - Last hour of current epoch (accepting early messages)
     */
    fun isInRotationWindow(timestamp: Long = System.currentTimeMillis()): Boolean {
        val progress = getEpochProgress(timestamp)
        return progress < ROTATION_WINDOW_START_MS || progress > ROTATION_WINDOW_END_MS
    }

    /**
     * Get the next rotation timestamp.
     */
    fun getNextRotationTime(timestamp: Long = System.currentTimeMillis()): Long {
        val currentEpoch = calculateEpoch(timestamp)
        return (currentEpoch + 1) * EPOCH_DURATION_MS
    }

    /**
     * Get time until next rotation in milliseconds.
     */
    fun getTimeUntilRotation(timestamp: Long = System.currentTimeMillis()): Long {
        return getNextRotationTime(timestamp) - timestamp
    }

    /**
     * Hash derived bytes to 32 bytes using SHA-256.
     * This produces the final mailbox address (64 hex characters).
     */
    private fun hashTo16Bytes(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val fullHash = sha256.digest(data)
        // Use full 32 bytes for mailbox address (64 hex chars)
        // This matches the database constraint: CHECK (length(mailbox_hash) = 64)
        return fullHash
    }

    /**
     * Convert bytes to lowercase hex string.
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    companion object {
        /**
         * Epoch duration: 25 hours in milliseconds.
         * Slightly longer than 24 hours to avoid daily rotation at same time.
         */
        val EPOCH_DURATION_MS = TimeUnit.HOURS.toMillis(25)

        /**
         * Rotation window: 1 hour at start of epoch (check previous mailbox).
         */
        private val ROTATION_WINDOW_START_MS = TimeUnit.HOURS.toMillis(1)

        /**
         * Rotation window: Last 1 hour of epoch (check next mailbox).
         */
        private val ROTATION_WINDOW_END_MS = EPOCH_DURATION_MS - TimeUnit.HOURS.toMillis(1)
    }
}

/**
 * Represents a mailbox address with metadata.
 */
data class MailboxAddress(
    /**
     * 64-character hex hash of the mailbox (matches database constraint).
     */
    val hash: String,

    /**
     * Epoch number this mailbox is valid for.
     */
    val epoch: Long,

    /**
     * Whether this is the primary mailbox to check.
     * During rotation windows, we check 2-3 mailboxes but only one is primary.
     */
    val isPrimary: Boolean
) {
    init {
        require(hash.length == 64) { "Mailbox hash must be 64 characters (32 bytes in hex)" }
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Mailbox hash must be lowercase hex" }
    }

    /**
     * Short form for logging (first 8 characters).
     */
    val short: String get() = hash.take(8) + "..."
}
