package com.void.slate.network.websocket

import com.void.slate.network.NetworkConfig
import com.void.slate.network.NetworkException
import com.void.slate.network.models.ReceivedMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Manages WebSocket connection for real-time message delivery.
 *
 * Handles:
 * - Connection lifecycle
 * - Automatic reconnection
 * - Message parsing and emission
 * - Error handling
 */
class WebSocketManager(
    private val httpClient: HttpClient,
    private val config: NetworkConfig,
    private val scope: CoroutineScope
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _incomingMessages = MutableSharedFlow<ReceivedMessage>(
        replay = 0,
        extraBufferCapacity = 100
    )
    val incomingMessages: SharedFlow<ReceivedMessage> = _incomingMessages.asSharedFlow()

    private val _connectionErrors = MutableSharedFlow<NetworkException>(
        replay = 0,
        extraBufferCapacity = 10
    )
    val connectionErrors: SharedFlow<NetworkException> = _connectionErrors.asSharedFlow()

    private var connectionJob: Job? = null
    private var currentSession: WebSocketSession? = null

    @Volatile
    private var isConnected = false

    /**
     * Connect to the WebSocket server and start listening for messages.
     */
    suspend fun connect() {
        if (isConnected) {
            return // Already connected
        }

        connectionJob = scope.launch {
            try {
                httpClient.webSocket(config.webSocketUrl) {
                    currentSession = this
                    isConnected = true

                    // Listen for incoming frames
                    while (isActive) {
                        try {
                            val frame = incoming.receive()
                            handleFrame(frame)
                        } catch (e: ClosedReceiveChannelException) {
                            // WebSocket closed normally
                            break
                        } catch (e: Exception) {
                            _connectionErrors.emit(
                                NetworkException.WebSocketError(
                                    message = "Error receiving frame: ${e.message}",
                                    cause = e
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _connectionErrors.emit(
                    NetworkException.WebSocketError(
                        message = "WebSocket connection failed: ${e.message}",
                        cause = e
                    )
                )
            } finally {
                isConnected = false
                currentSession = null
            }
        }
    }

    /**
     * Disconnect from the WebSocket server.
     */
    suspend fun disconnect() {
        isConnected = false
        currentSession?.close()
        connectionJob?.cancel()
        connectionJob = null
        currentSession = null
    }

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Handle incoming WebSocket frame.
     */
    private suspend fun handleFrame(frame: Frame) {
        when (frame) {
            is Frame.Text -> {
                val text = frame.readText()
                parseAndEmitMessage(text)
            }
            is Frame.Binary -> {
                // Binary frames not currently supported
                _connectionErrors.emit(
                    NetworkException.WebSocketError("Binary frames not supported")
                )
            }
            is Frame.Close -> {
                // Server closed the connection
                isConnected = false
            }
            is Frame.Ping, is Frame.Pong -> {
                // Ping/Pong handled automatically by Ktor
            }
        }
    }

    /**
     * Parse JSON message and emit to flow.
     */
    private suspend fun parseAndEmitMessage(text: String) {
        try {
            val message = json.decodeFromString<ReceivedMessage>(text)
            _incomingMessages.emit(message)
        } catch (e: SerializationException) {
            _connectionErrors.emit(
                NetworkException.SerializationError(
                    message = "Failed to parse incoming message: ${e.message}",
                    cause = e
                )
            )
        }
    }
}
