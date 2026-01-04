package com.void.slate.network.auth

import android.util.Log
import com.void.slate.crypto.CryptoProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages ephemeral query tokens for secure mailbox access.
 *
 * ## Purpose
 * - Provides proof-of-ownership for mailbox queries without revealing identity_seed
 * - Generates HMAC challenges to prove knowledge of identity_seed
 * - Requests short-lived tokens from server
 * - Caches tokens until they expire
 *
 * ## Privacy
 * - Server never sees identity_seed
 * - Server cannot link mailbox hashes to user identities
 * - Tokens are single-use and expire after 10 minutes
 */
class EphemeralTokenManager(
    private val supabase: SupabaseClient,
    private val crypto: CryptoProvider
) {

    // Token cache: mailbox_hash -> (token_id, expires_at)
    private val tokenCache = mutableMapOf<String, CachedToken>()

    /**
     * Get a valid token for a mailbox hash.
     * Returns cached token if still valid, otherwise requests new one.
     *
     * @param identitySeed The user's 32-byte identity seed
     * @param mailboxHash The 64-char hex mailbox hash
     * @return Token ID (UUID)
     */
    suspend fun getToken(identitySeed: ByteArray, mailboxHash: String): Result<UUID> {
        return try {
            require(identitySeed.size == 32) { "Identity seed must be 32 bytes" }
            require(mailboxHash.length == 64) { "Mailbox hash must be 64 characters" }

            // Check cache first
            val cached = tokenCache[mailboxHash]
            if (cached != null && !cached.isExpired()) {
                Log.d(TAG, "📋 Using cached token for mailbox ${mailboxHash.take(8)}...")
                return Result.success(cached.tokenId)
            }

            // Request new token
            Log.d(TAG, "🔑 Requesting new token for mailbox ${mailboxHash.take(8)}...")
            val token = requestToken(identitySeed, mailboxHash)

            // Cache it
            tokenCache[mailboxHash] = token

            Log.d(TAG, "✅ Token acquired: ${token.tokenId} (expires at ${token.expiresAt})")
            Result.success(token.tokenId)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get token: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Request a new ephemeral token from server.
     * Generates HMAC proof-of-ownership challenge.
     */
    private suspend fun requestToken(identitySeed: ByteArray, mailboxHash: String): CachedToken {
        // Generate timestamp (seconds since epoch)
        val timestamp = System.currentTimeMillis() / 1000

        // Generate HMAC challenge: HMAC-SHA256(identity_seed, "token_request:" + mailbox_hash + timestamp)
        val message = "token_request:$mailboxHash$timestamp"
        val challenge = computeHmac(identitySeed, message)

        Log.d(TAG, "  📝 Challenge message: $message")
        Log.d(TAG, "  🔐 Challenge HMAC: ${challenge.take(16)}...")

        // Call PostgreSQL function via RPC
        val response = supabase.postgrest.rpc(
            function = "request_query_token",
            parameters = TokenRequest(
                p_mailbox_hash = mailboxHash,
                p_timestamp = timestamp,
                p_challenge = challenge
            )
        ).decodeSingle<TokenResponse>()

        return CachedToken(
            tokenId = UUID.fromString(response.token_id),
            expiresAt = response.expires_at
        )
    }

    /**
     * Compute HMAC-SHA256 of message using key.
     */
    private fun computeHmac(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        // Return as hex string
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Clear cached tokens (e.g., when identity changes).
     */
    fun clearCache() {
        tokenCache.clear()
        Log.d(TAG, "🗑️  Token cache cleared")
    }

    companion object {
        private const val TAG = "EphemeralTokenManager"
    }
}

/**
 * Cached token with expiration.
 */
private data class CachedToken(
    val tokenId: UUID,
    val expiresAt: String  // ISO 8601 timestamp
) {
    /**
     * Check if token is expired (with 1-minute buffer).
     */
    fun isExpired(): Boolean {
        val expiryTime = java.time.Instant.parse(expiresAt)
        val now = java.time.Instant.now()
        val buffer = java.time.Duration.ofMinutes(1)
        return now.plus(buffer).isAfter(expiryTime)
    }
}

/**
 * Request payload for token request.
 */
@Serializable
private data class TokenRequest(
    @SerialName("p_mailbox_hash")
    val p_mailbox_hash: String,

    @SerialName("p_timestamp")
    val p_timestamp: Long,

    @SerialName("p_challenge")
    val p_challenge: String
)

/**
 * Response from token request.
 */
@Serializable
private data class TokenResponse(
    @SerialName("token_id")
    val token_id: String,

    @SerialName("expires_at")
    val expires_at: String
)
