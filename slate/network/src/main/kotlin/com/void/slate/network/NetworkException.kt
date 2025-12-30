package com.void.slate.network

/**
 * Base exception for network-related errors.
 */
sealed class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Server returned an error response.
     */
    class ServerError(
        val statusCode: Int,
        message: String,
        cause: Throwable? = null
    ) : NetworkException("Server error $statusCode: $message", cause)

    /**
     * Network request timed out.
     */
    class Timeout(
        message: String = "Network request timed out",
        cause: Throwable? = null
    ) : NetworkException(message, cause)

    /**
     * No network connectivity available.
     */
    class NoConnectivity(
        message: String = "No network connection available",
        cause: Throwable? = null
    ) : NetworkException(message, cause)

    /**
     * Failed to serialize/deserialize request or response.
     */
    class SerializationError(
        message: String,
        cause: Throwable? = null
    ) : NetworkException("Serialization error: $message", cause)

    /**
     * WebSocket connection failed.
     */
    class WebSocketError(
        message: String,
        cause: Throwable? = null
    ) : NetworkException("WebSocket error: $message", cause)

    /**
     * Generic network error (e.g., DNS failure, SSL error).
     */
    class ConnectionError(
        message: String,
        cause: Throwable? = null
    ) : NetworkException("Connection error: $message", cause)

    /**
     * Server rejected the request (authentication, validation, etc.).
     */
    class RequestRejected(
        message: String,
        cause: Throwable? = null
    ) : NetworkException("Request rejected: $message", cause)

    /**
     * Rate limit exceeded.
     */
    class RateLimitExceeded(
        val retryAfterMs: Long? = null,
        message: String = "Rate limit exceeded",
        cause: Throwable? = null
    ) : NetworkException(message, cause)
}
