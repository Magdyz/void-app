package com.void.slate.network.impl

import com.void.slate.network.NetworkException
import kotlinx.coroutines.delay

/**
 * Retry policy with exponential backoff for network operations.
 *
 * Handles transient failures by retrying requests with increasing delays.
 */
class RetryPolicy(
    private val maxAttempts: Int = 3,
    private val baseBackoffMs: Long = 1000
) {
    /**
     * Execute an operation with retry logic.
     *
     * @param operation The suspending operation to execute
     * @return Result of the operation or the last failure
     */
    suspend fun <T> executeWithRetry(
        operation: suspend () -> Result<T>
    ): Result<T> {
        var lastFailure: Throwable? = null

        repeat(maxAttempts) { attempt ->
            val result = operation()

            if (result.isSuccess) {
                return result
            }

            // Store the failure
            lastFailure = result.exceptionOrNull()

            // Don't retry on certain errors
            if (!shouldRetry(lastFailure)) {
                return result
            }

            // Apply exponential backoff before retry (except on last attempt)
            if (attempt < maxAttempts - 1) {
                val backoffDelay = calculateBackoff(attempt)
                delay(backoffDelay)
            }
        }

        // All retries exhausted, return the last failure
        return Result.failure(
            lastFailure ?: NetworkException.ConnectionError("Max retry attempts exceeded")
        )
    }

    /**
     * Calculate exponential backoff delay.
     *
     * Formula: baseBackoff * 2^attempt
     * Example: 1000ms, 2000ms, 4000ms, ...
     */
    private fun calculateBackoff(attempt: Int): Long {
        return baseBackoffMs * (1 shl attempt)  // 2^attempt
    }

    /**
     * Determine if an error is retryable.
     *
     * Non-retryable errors:
     * - Client errors (4xx) - bad request, authentication issues
     * - Serialization errors - won't fix themselves
     * - Request rejected - validation failed
     *
     * Retryable errors:
     * - Server errors (5xx) - might be transient
     * - Timeout - might succeed on retry
     * - Connection errors - network might recover
     * - No connectivity - might come back
     */
    private fun shouldRetry(error: Throwable?): Boolean {
        return when (error) {
            is NetworkException.ServerError -> true
            is NetworkException.Timeout -> true
            is NetworkException.ConnectionError -> true
            is NetworkException.NoConnectivity -> true
            is NetworkException.WebSocketError -> true

            // Don't retry these
            is NetworkException.RequestRejected -> false
            is NetworkException.SerializationError -> false
            is NetworkException.RateLimitExceeded -> false

            // Unknown errors: retry to be safe
            else -> true
        }
    }
}
