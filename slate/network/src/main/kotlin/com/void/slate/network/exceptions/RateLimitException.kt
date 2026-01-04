package com.void.slate.network.exceptions

/**
 * Exception thrown when a rate limit is exceeded.
 *
 * This occurs when the server-side rate limiting triggers fire:
 * - Message insert rate limit: 100 messages per hour per mailbox
 * - Token request rate limit: 50 token requests per hour per mailbox
 *
 * ## Client Handling
 * - Display user-friendly error message
 * - Queue message locally for retry after cooldown period
 * - Show countdown timer until rate limit window resets
 */
class RateLimitException(
    message: String,
    val requestType: RateLimitType = RateLimitType.MESSAGE_SEND
) : Exception(message) {

    /**
     * Get a user-friendly error message for display.
     */
    fun getUserMessage(): String {
        return when (requestType) {
            RateLimitType.MESSAGE_SEND ->
                "You've sent too many messages. Please wait before sending more."
            RateLimitType.TOKEN_REQUEST ->
                "Too many authentication requests. Please wait a moment."
        }
    }
}

/**
 * Type of rate limit that was exceeded.
 */
enum class RateLimitType {
    MESSAGE_SEND,
    TOKEN_REQUEST
}
