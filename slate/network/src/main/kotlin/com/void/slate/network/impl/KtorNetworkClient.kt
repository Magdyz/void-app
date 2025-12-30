package com.void.slate.network.impl

import com.void.slate.network.ConnectionState
import com.void.slate.network.NetworkClient
import com.void.slate.network.NetworkConfig
import com.void.slate.network.NetworkException
import com.void.slate.network.models.ContactExchangeRequest
import com.void.slate.network.models.ContactExchangeResponse
import com.void.slate.network.models.MessageSendRequest
import com.void.slate.network.models.MessageSendResponse
import com.void.slate.network.models.ReceivedMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Ktor-based implementation of NetworkClient.
 *
 * Handles HTTP requests and WebSocket connections for message and contact synchronization.
 * Uses retry policy for transient failures.
 */
class KtorNetworkClient(
    private val httpClient: HttpClient,
    private val config: NetworkConfig,
    private val retryPolicy: RetryPolicy
) : NetworkClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override fun observeConnectionState(): Flow<ConnectionState> = _connectionState.asStateFlow()

    // WebSocket manager for real-time messaging
    private val webSocketManager by lazy {
        com.void.slate.network.websocket.WebSocketManager(
            httpClient = httpClient,
            config = config,
            scope = scope
        )
    }

    init {
        // Monitor WebSocket errors and update connection state
        scope.launch {
            webSocketManager.connectionErrors.collect { error ->
                _connectionState.value = ConnectionState.Error(error.message ?: "WebSocket error")
            }
        }
    }

    override suspend fun sendMessage(request: MessageSendRequest): Result<MessageSendResponse> {
        return retryPolicy.executeWithRetry {
            executeRequest {
                val response = httpClient.post("${config.apiBaseUrl}/messages/send") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                response.body<MessageSendResponse>()
            }
        }
    }

    override suspend fun receiveMessages(since: Long?): Result<List<ReceivedMessage>> {
        return retryPolicy.executeWithRetry {
            executeRequest {
                val response = httpClient.get("${config.apiBaseUrl}/messages") {
                    since?.let { parameter("since", it) }
                }
                response.body<List<ReceivedMessage>>()
            }
        }
    }

    override suspend fun sendContactRequest(request: ContactExchangeRequest): Result<ContactExchangeResponse> {
        return retryPolicy.executeWithRetry {
            executeRequest {
                val response = httpClient.post("${config.apiBaseUrl}/contacts/request") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                response.body<ContactExchangeResponse>()
            }
        }
    }

    override suspend fun pollContactRequests(): Result<List<ContactExchangeRequest>> {
        return retryPolicy.executeWithRetry {
            executeRequest {
                val response = httpClient.get("${config.apiBaseUrl}/contacts/requests")
                response.body<List<ContactExchangeRequest>>()
            }
        }
    }

    override fun observeIncomingMessages(): Flow<ReceivedMessage> {
        return webSocketManager.incomingMessages
    }

    override suspend fun connect() {
        if (!config.enableWebSockets) {
            _connectionState.value = ConnectionState.Connected
            return
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            webSocketManager.connect()
            _connectionState.value = ConnectionState.Connected
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            throw e
        }
    }

    override suspend fun disconnect() {
        webSocketManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Wrapper to convert HTTP exceptions into NetworkException.
     */
    private suspend fun <T> executeRequest(block: suspend () -> T): Result<T> {
        return try {
            val result = block()
            Result.success(result)
        } catch (e: ClientRequestException) {
            // 4xx errors (client-side issues)
            Result.failure(
                NetworkException.RequestRejected(
                    message = "Request rejected with status ${e.response.status.value}",
                    cause = e
                )
            )
        } catch (e: ServerResponseException) {
            // 5xx errors (server-side issues)
            Result.failure(
                NetworkException.ServerError(
                    statusCode = e.response.status.value,
                    message = "Server error: ${e.message}",
                    cause = e
                )
            )
        } catch (e: SocketTimeoutException) {
            Result.failure(NetworkException.Timeout(cause = e))
        } catch (e: UnknownHostException) {
            Result.failure(NetworkException.NoConnectivity(cause = e))
        } catch (e: IOException) {
            Result.failure(
                NetworkException.ConnectionError(
                    message = e.message ?: "Network connection failed",
                    cause = e
                )
            )
        } catch (e: SerializationException) {
            Result.failure(
                NetworkException.SerializationError(
                    message = e.message ?: "Failed to serialize/deserialize data",
                    cause = e
                )
            )
        } catch (e: Exception) {
            Result.failure(
                NetworkException.ConnectionError(
                    message = "Unexpected error: ${e.message}",
                    cause = e
                )
            )
        }
    }
}
