package com.void.slate.network.supabase

import android.util.Log
import com.void.slate.network.auth.EphemeralTokenManager
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Fetches messages from Supabase using fetch-mailbox Edge Function.
 *
 * ## Poisson Ghost Protocol
 * This client implements the privacy-enhanced message fetching:
 * - Always receives exactly 4KB responses (padded or noise)
 * - Magic byte indicates real messages vs noise
 * - ISP/Google cannot determine message presence from response size
 * - Constant traffic pattern hides communication metadata
 *
 * ## Response Format
 * - Byte 0: Magic byte (0x01 = real messages, 0x00 = noise)
 * - Byte 1-4095: Message JSON + padding, OR random noise
 *
 * ## Usage
 * ```kotlin
 * val client = FetchMailboxClient(httpClient, tokenManager, supabaseUrl)
 * val messages = client.fetchMessages(identitySeed, mailboxHash, epoch)
 * ```
 */
class FetchMailboxClient(
    private val httpClient: HttpClient,
    private val tokenManager: EphemeralTokenManager,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String
) {

    companion object {
        private const val TAG = "FetchMailboxClient"

        // Response constants
        private const val RESPONSE_SIZE = 4096
        private const val MAGIC_BYTE_REAL = 0x01
        private const val MAGIC_BYTE_NOISE = 0x00

        // Epoch window for clock skew tolerance
        private const val DEFAULT_EPOCH_WINDOW = 3600L // ±1 hour
    }

    /**
     * Fetch messages from mailbox using fetch-mailbox Edge Function.
     *
     * @param identitySeed User's 32-byte identity seed (for token generation)
     * @param mailboxHash Mailbox hash to fetch from (64-char hex)
     * @param epoch Current epoch timestamp
     * @param epochWindow Tolerance window for clock skew (default ±1 hour)
     * @return Result containing list of messages (empty if noise response)
     */
    suspend fun fetchMessages(
        identitySeed: ByteArray,
        mailboxHash: String,
        epoch: Long,
        epochWindow: Long = DEFAULT_EPOCH_WINDOW
    ): Result<List<MessageRecord>> {
        return try {
            require(mailboxHash.length == 64) { "Mailbox hash must be 64 characters" }

            Log.d(TAG, "📥 Fetching mailbox ${mailboxHash.take(8)}... via Poisson Ghost protocol")

            // Get ephemeral token for authentication
            val tokenResult = tokenManager.getToken(identitySeed, mailboxHash)
            if (tokenResult.isFailure) {
                Log.e(TAG, "❌ Failed to get token: ${tokenResult.exceptionOrNull()?.message}")
                return Result.failure(tokenResult.exceptionOrNull() ?: Exception("Token request failed"))
            }

            val tokenId = tokenResult.getOrThrow()

            // Build Edge Function URL
            val functionUrl = "$supabaseUrl/functions/v1/fetch-mailbox"

            // Prepare request body
            val requestBody = JSONObject().apply {
                put("mailbox_hash", mailboxHash)
                put("epoch", epoch)
                put("epoch_window", epochWindow)
            }.toString()

            Log.d(TAG, "🔍 Calling fetch-mailbox Edge Function")
            Log.d(TAG, "   URL: $functionUrl")
            Log.d(TAG, "   Mailbox: ${mailboxHash.take(8)}...")
            Log.d(TAG, "   Epoch: $epoch (±$epochWindow)")
            Log.d(TAG, "   Token: $tokenId")

            // Make HTTP POST request to Edge Function
            val response: HttpResponse = httpClient.post(functionUrl) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $supabaseAnonKey")  // Required by Supabase Edge Functions
                header("x-mailbox-token", tokenId.toString())       // Our custom auth token (lowercase for Supabase RLS)
                setBody(requestBody)
            }

            // Read binary response
            val responseBytes = response.readBytes()

            // Validate response size
            if (responseBytes.size != RESPONSE_SIZE) {
                // Log the actual error response for debugging
                val errorText = responseBytes.toString(Charsets.UTF_8)
                Log.e(TAG, "❌ Invalid response size: ${responseBytes.size} bytes (expected $RESPONSE_SIZE)")
                Log.e(TAG, "   HTTP Status: ${response.status.value}")
                Log.e(TAG, "   Response body: $errorText")
                return Result.failure(Exception("Invalid response size: ${response.status.value} - $errorText"))
            }

            // Check magic byte
            val magicByte = responseBytes[0].toInt() and 0xFF

            when (magicByte) {
                MAGIC_BYTE_REAL -> {
                    Log.d(TAG, "✓ Real messages detected (magic byte: 0x01)")
                    parseMessagesFromResponse(responseBytes)
                }
                MAGIC_BYTE_NOISE -> {
                    Log.d(TAG, "○ Noise response (magic byte: 0x00) - no messages")
                    Result.success(emptyList())
                }
                else -> {
                    Log.w(TAG, "⚠️  Unknown magic byte: 0x${magicByte.toString(16)}")
                    Result.success(emptyList()) // Treat unknown as noise
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch mailbox: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Parse messages from 2KB response.
     * Extracts JSON from bytes 1-N and deserializes message list.
     */
    private fun parseMessagesFromResponse(responseBytes: ByteArray): Result<List<MessageRecord>> {
        return try {
            // Skip first byte (magic byte)
            val dataBytes = responseBytes.sliceArray(1 until responseBytes.size)

            // Find the end of JSON by counting braces
            // Server sends: {"messages":[...]} followed by random padding
            // We need to find where the JSON object properly closes
            var openBraces = 0
            var openBrackets = 0
            var jsonEndIndex = -1
            var inString = false
            var escapeNext = false

            for (i in dataBytes.indices) {
                val byte = dataBytes[i]
                val char = byte.toInt().toChar()

                // Handle string escaping
                if (escapeNext) {
                    escapeNext = false
                    continue
                }

                if (char == '\\' && inString) {
                    escapeNext = true
                    continue
                }

                // Track if we're inside a string
                if (char == '"') {
                    inString = !inString
                    continue
                }

                // Only count braces/brackets outside of strings
                if (!inString) {
                    when (char) {
                        '{' -> openBraces++
                        '}' -> {
                            openBraces--
                            // If we've closed all braces and brackets, we found the end
                            if (openBraces == 0 && openBrackets == 0) {
                                jsonEndIndex = i + 1
                                break
                            }
                        }
                        '[' -> openBrackets++
                        ']' -> openBrackets--
                    }
                }
            }

            if (jsonEndIndex < 0) {
                Log.d(TAG, "   ○ No complete JSON found - no messages")
                return Result.success(emptyList())
            }

            // Extract valid JSON bytes
            val jsonBytes = dataBytes.sliceArray(0 until jsonEndIndex)
            val jsonString = jsonBytes.toString(Charsets.UTF_8)

            if (jsonString.isBlank()) {
                Log.d(TAG, "   ○ Empty JSON - no messages")
                return Result.success(emptyList())
            }

            Log.d(TAG, "   JSON length: ${jsonString.length} bytes")

            // Parse JSON response
            val json = Json { ignoreUnknownKeys = true }
            val response = json.decodeFromString<FetchMailboxResponse>(jsonString)

            Log.d(TAG, "   ✓ Parsed ${response.messages.size} message(s)")

            Result.success(response.messages)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse messages: ${e.message}", e)
            Result.failure(e)
        }
    }
}

/**
 * Response wrapper for fetch-mailbox Edge Function.
 * Uses MessageRecord from MessageFetcher for compatibility.
 */
@Serializable
private data class FetchMailboxResponse(
    @SerialName("messages")
    val messages: List<MessageRecord>
)
