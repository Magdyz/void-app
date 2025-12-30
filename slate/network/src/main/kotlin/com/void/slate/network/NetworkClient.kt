package com.void.slate.network

import com.void.slate.network.models.ContactExchangeRequest
import com.void.slate.network.models.ContactExchangeResponse
import com.void.slate.network.models.MessageSendRequest
import com.void.slate.network.models.MessageSendResponse
import com.void.slate.network.models.ReceivedMessage
import kotlinx.coroutines.flow.Flow

/**
 * Network client interface for VOID message and contact synchronization.
 *
 * This is a transport-only layer that handles already E2E encrypted payloads.
 * It does NOT see plaintext content or perform encryption/decryption.
 *
 * Implementations:
 * - KtorNetworkClient: Real HTTP/WebSocket implementation
 * - MockNetworkClient: Local testing without server
 */
interface NetworkClient {

    /**
     * Send an encrypted message to a recipient.
     *
     * @param request Message with already E2E encrypted payload
     * @return Result with server response or network error
     */
    suspend fun sendMessage(request: MessageSendRequest): Result<MessageSendResponse>

    /**
     * Poll for incoming messages from the server.
     *
     * @param since Optional timestamp to get messages after this time
     * @return Result with list of received messages or network error
     */
    suspend fun receiveMessages(since: Long? = null): Result<List<ReceivedMessage>>

    /**
     * Send a contact request to another user via the server.
     *
     * @param request Contact exchange request with public keys
     * @return Result with acceptance response or network error
     */
    suspend fun sendContactRequest(request: ContactExchangeRequest): Result<ContactExchangeResponse>

    /**
     * Poll for incoming contact requests.
     *
     * @return Result with list of pending contact requests or network error
     */
    suspend fun pollContactRequests(): Result<List<ContactExchangeRequest>>

    /**
     * Observe incoming messages in real-time via WebSocket.
     *
     * @return Flow of received messages (hot stream)
     */
    fun observeIncomingMessages(): Flow<ReceivedMessage>

    /**
     * Observe connection state changes.
     *
     * @return Flow of connection state updates
     */
    fun observeConnectionState(): Flow<ConnectionState>

    /**
     * Manually connect to the server (WebSocket).
     * Usually called automatically when observing messages.
     */
    suspend fun connect()

    /**
     * Disconnect from the server.
     */
    suspend fun disconnect()
}

/**
 * Connection state of the network client.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
