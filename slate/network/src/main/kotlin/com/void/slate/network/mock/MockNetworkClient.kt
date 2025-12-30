package com.void.slate.network.mock

import com.void.slate.network.ConnectionState
import com.void.slate.network.NetworkClient
import com.void.slate.network.models.ContactExchangeRequest
import com.void.slate.network.models.ContactExchangeResponse
import com.void.slate.network.models.MessageSendRequest
import com.void.slate.network.models.MessageSendResponse
import com.void.slate.network.models.ReceivedMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of NetworkClient for local testing.
 *
 * Features:
 * - In-memory message storage
 * - Configurable latency simulation
 * - Message loopback (sent messages appear as received)
 * - Contact request handling
 * - No actual network calls
 *
 * Perfect for:
 * - Local development without a server
 * - Unit testing
 * - Integration testing
 */
class MockNetworkClient(
    private val simulatedLatencyMs: Long = 100L,
    private val errorRate: Float = 0f  // 0.0 to 1.0 (0% to 100%)
) : NetworkClient {

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override fun observeConnectionState(): Flow<ConnectionState> = _connectionState.asStateFlow()

    // Incoming message stream
    private val _incomingMessages = MutableSharedFlow<ReceivedMessage>(
        replay = 0,
        extraBufferCapacity = 100
    )
    override fun observeIncomingMessages(): Flow<ReceivedMessage> = _incomingMessages.asSharedFlow()

    // In-memory storage
    private val messageStorage = ConcurrentHashMap<String, MutableList<ReceivedMessage>>()
    private val contactRequests = ConcurrentHashMap<String, MutableList<ContactExchangeRequest>>()
    private val sentMessages = mutableListOf<MessageSendRequest>()

    // Test helpers
    val sentMessagesHistory: List<MessageSendRequest> get() = sentMessages.toList()

    override suspend fun sendMessage(request: MessageSendRequest): Result<MessageSendResponse> {
        simulateNetworkDelay()

        if (shouldSimulateError()) {
            return Result.failure(Exception("Simulated network error"))
        }

        // Store sent message for verification
        sentMessages.add(request)

        // Simulate message loopback: Echo the message back as received
        // This simulates sending a message to yourself for testing
        val receivedMessage = ReceivedMessage(
            messageId = request.messageId,
            senderIdentity = request.recipientIdentity,  // Simulate it came from recipient
            encryptedPayload = request.encryptedPayload,
            serverTimestamp = System.currentTimeMillis()
        )

        // Store in message storage (by recipient identity)
        messageStorage.getOrPut(request.recipientIdentity) { mutableListOf() }
            .add(receivedMessage)

        // Emit to incoming message stream (simulate real-time delivery)
        _incomingMessages.emit(receivedMessage)

        return Result.success(
            MessageSendResponse(
                success = true,
                serverTimestamp = System.currentTimeMillis(),
                messageId = request.messageId
            )
        )
    }

    override suspend fun receiveMessages(since: Long?): Result<List<ReceivedMessage>> {
        simulateNetworkDelay()

        if (shouldSimulateError()) {
            return Result.failure(Exception("Simulated network error"))
        }

        // Collect all messages from all conversations
        val allMessages = messageStorage.values.flatten()

        // Filter by timestamp if provided
        val filtered = if (since != null) {
            allMessages.filter { it.serverTimestamp > since }
        } else {
            allMessages
        }

        return Result.success(filtered.sortedBy { it.serverTimestamp })
    }

    override suspend fun sendContactRequest(request: ContactExchangeRequest): Result<ContactExchangeResponse> {
        simulateNetworkDelay()

        if (shouldSimulateError()) {
            return Result.failure(Exception("Simulated network error"))
        }

        // Store contact request for the recipient to poll
        contactRequests.getOrPut(request.toIdentity) { mutableListOf() }
            .add(request)

        return Result.success(
            ContactExchangeResponse(
                success = true,
                accepted = false,  // Acceptance happens when recipient responds
                contactId = null   // Will be set when accepted
            )
        )
    }

    override suspend fun pollContactRequests(): Result<List<ContactExchangeRequest>> {
        simulateNetworkDelay()

        if (shouldSimulateError()) {
            return Result.failure(Exception("Simulated network error"))
        }

        // In a real implementation, this would poll for requests sent to the current user
        // For mock, we return all pending requests
        val allRequests = contactRequests.values.flatten()

        return Result.success(allRequests)
    }

    override suspend fun connect() {
        _connectionState.value = ConnectionState.Connecting
        simulateNetworkDelay()
        _connectionState.value = ConnectionState.Connected
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    // ═══════════════════════════════════════════════════════════════════
    // Test Helpers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Manually inject a message as if it came from the server.
     * Useful for testing message reception.
     */
    suspend fun injectIncomingMessage(message: ReceivedMessage) {
        messageStorage.getOrPut(message.senderIdentity) { mutableListOf() }
            .add(message)
        _incomingMessages.emit(message)
    }

    /**
     * Clear all stored messages and requests.
     */
    fun clearAll() {
        messageStorage.clear()
        contactRequests.clear()
        sentMessages.clear()
    }

    /**
     * Get all messages sent to a specific identity.
     */
    fun getMessagesFor(identity: String): List<ReceivedMessage> {
        return messageStorage[identity]?.toList() ?: emptyList()
    }

    /**
     * Get all contact requests for a specific identity.
     */
    fun getContactRequestsFor(identity: String): List<ContactExchangeRequest> {
        return contactRequests[identity]?.toList() ?: emptyList()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun simulateNetworkDelay() {
        if (simulatedLatencyMs > 0) {
            delay(simulatedLatencyMs)
        }
    }

    private fun shouldSimulateError(): Boolean {
        return errorRate > 0 && Math.random() < errorRate
    }
}
