# VOID Network Module

The `slate/network` module provides networking infrastructure for VOID, handling message and contact synchronization over HTTP/WebSocket.

## Architecture

The network layer is a **transport-only** module - it handles already E2E encrypted payloads without seeing plaintext content.

```
┌─────────────────────────────────────┐
│   Blocks (messaging, contacts)      │
│   - Encrypt data before sending     │
│   - Decrypt data after receiving    │
└──────────────┬──────────────────────┘
               │
               v
┌─────────────────────────────────────┐
│   slate/network                      │
│   - Transport encrypted payloads     │
│   - HTTP POST/GET operations         │
│   - WebSocket real-time streaming    │
│   - Retry logic & error handling     │
└─────────────────────────────────────┘
```

## Key Components

### NetworkClient Interface

The core abstraction for all network operations:

```kotlin
interface NetworkClient {
    suspend fun sendMessage(request: MessageSendRequest): Result<MessageSendResponse>
    suspend fun receiveMessages(since: Long?): Result<List<ReceivedMessage>>
    suspend fun sendContactRequest(request: ContactExchangeRequest): Result<ContactExchangeResponse>
    suspend fun pollContactRequests(): Result<List<ContactExchangeRequest>>

    fun observeIncomingMessages(): Flow<ReceivedMessage>
    fun observeConnectionState(): Flow<ConnectionState>

    suspend fun connect()
    suspend fun disconnect()
}
```

### Implementations

#### 1. KtorNetworkClient (Production)

Real HTTP/WebSocket implementation using Ktor:
- **HTTP**: POST for sending, GET for polling
- **WebSocket**: Real-time message delivery
- **Retry Policy**: Exponential backoff for transient failures
- **Error Handling**: Maps HTTP exceptions to NetworkException types

#### 2. MockNetworkClient (Development)

Local testing implementation with no server required:
- **In-memory storage**: Messages and contact requests
- **Message loopback**: Sent messages appear as received
- **Configurable latency**: Simulate network delays
- **Error injection**: Test failure scenarios
- **Perfect for**: Local development, unit tests, integration tests

### Configuration

Three preset configurations available:

```kotlin
// Production (points to real server - not yet implemented)
NetworkConfig.DEFAULT

// Local development (uses MockNetworkClient)
NetworkConfig.LOCAL_MOCK

// Debug with local server (Android emulator)
NetworkConfig.DEBUG
```

## Usage

### Basic Setup (via Koin DI)

The network module is automatically configured in `AppModule`:

```kotlin
val appModule = module {
    includes(networkModule)  // Registers NetworkClient
}
```

### Sending Messages

```kotlin
class MessageRepository(
    private val networkClient: NetworkClient
) {
    suspend fun sendMessage(message: Message) {
        // 1. Store locally
        storeMessageLocally(message)

        // 2. Send via network
        val request = MessageSendRequest(
            messageId = message.id,
            recipientIdentity = "word1.word2.word3",
            encryptedPayload = encryptedData,  // Already encrypted!
            timestamp = message.timestamp
        )

        networkClient.sendMessage(request)
            .onSuccess { updateMessageStatus(message.id, MessageStatus.SENT) }
            .onFailure { updateMessageStatus(message.id, MessageStatus.FAILED) }
    }
}
```

### Receiving Messages

```kotlin
// Option 1: Polling (HTTP GET)
suspend fun syncMessages() {
    networkClient.receiveMessages(lastSyncTimestamp)
        .onSuccess { messages ->
            messages.forEach { receiveMessage(it) }
        }
}

// Option 2: Real-time (WebSocket)
networkClient.observeIncomingMessages()
    .collect { message ->
        receiveMessage(message)
    }
```

### Contact Requests

```kotlin
// Send contact request
val request = ContactExchangeRequest(
    requestId = UUID.randomUUID().toString(),
    fromIdentity = "alpha.beta.gamma",
    toIdentity = "delta.echo.foxtrot",
    publicKeyBundle = myPublicKeys,
    timestamp = System.currentTimeMillis()
)

networkClient.sendContactRequest(request)

// Poll for incoming requests
networkClient.pollContactRequests()
    .onSuccess { requests ->
        requests.forEach { handleContactRequest(it) }
    }
```

## Network Protocol

### Message Sync

**Outbound (HTTP POST)**:
```
POST /v1/messages/send
Content-Type: application/json

{
  "messageId": "uuid",
  "recipientIdentity": "word1.word2.word3",
  "encryptedPayload": [byte array],
  "timestamp": 1234567890
}
```

**Inbound (HTTP GET or WebSocket)**:
```
GET /v1/messages?since=1234567890

Response:
[
  {
    "messageId": "uuid",
    "senderIdentity": "word1.word2.word3",
    "encryptedPayload": [byte array],
    "serverTimestamp": 1234567890
  }
]
```

**WebSocket Stream**:
```
ws://server/ws
→ Receives ReceivedMessage objects as JSON
```

### Contact Exchange

```
POST /v1/contacts/request
{
  "requestId": "uuid",
  "fromIdentity": "word1.word2.word3",
  "toIdentity": "word4.word5.word6",
  "publicKeyBundle": [byte array],
  "timestamp": 1234567890
}
```

## Error Handling

Network operations return `Result<T>` and can fail with:

```kotlin
sealed class NetworkException : Exception {
    class ServerError(val statusCode: Int)
    class Timeout
    class NoConnectivity
    class SerializationError
    class WebSocketError
    class ConnectionError
    class RequestRejected
    class RateLimitExceeded(val retryAfterMs: Long?)
}
```

Retry policy automatically retries:
- Server errors (5xx)
- Timeouts
- Connection errors
- No connectivity

Does NOT retry:
- Client errors (4xx) - bad request, auth issues
- Serialization errors
- Rate limit exceeded

## Testing

### Unit Tests

```kotlin
@Test
fun `mock client simulates message loopback`() = runTest {
    val mock = MockNetworkClient()

    val request = MessageSendRequest(...)
    mock.sendMessage(request)

    val received = mock.observeIncomingMessages().first()
    assertThat(received.messageId).isEqualTo(request.messageId)
}
```

### Integration Tests

```kotlin
@Test
fun `repository sends message via network`() = runTest {
    val mockNetwork = MockNetworkClient()
    val repository = MessageRepository(storage, mockNetwork)

    repository.sendMessage(testMessage)

    assertThat(mockNetwork.sentMessagesHistory).hasSize(1)
}
```

## Security Considerations

### What Network Layer DOES:
- Transport encrypted payloads (E2E encrypted by crypto layer)
- Route messages to recipients by identity
- Provide reliable delivery (retry, queuing)

### What Network Layer DOES NOT:
- See plaintext message content (E2E encrypted)
- Verify message authenticity (handled by crypto layer)
- Store keys or secrets
- Perform encryption/decryption

### Metadata Visibility

**Current**: Server can see sender/recipient identities and timestamps

**Future (Phase 4)**:
- Onion routing for metadata protection
- Traffic padding
- Timing obfuscation

## Development

### Mock Mode (Default)

By default, the app uses `NetworkConfig.LOCAL_MOCK`:
- No server required
- Instant message delivery
- Perfect for UI development
- All data in-memory only

### Switching to Real Server

Update `NetworkModule.kt`:

```kotlin
single {
    NetworkConfig(
        serverUrl = "https://your-server.com",
        mockMode = false
    )
}
```

## Future Enhancements

### Phase 4: Privacy
- Onion routing
- Anonymous message relay
- Traffic analysis resistance

### Phase 5: P2P
- Direct device-to-device (WiFi Direct, Bluetooth)
- Mesh networking
- Distributed relay network

## Files

```
slate/network/
├── NetworkClient.kt              # Core interface
├── NetworkConfig.kt              # Configuration
├── NetworkException.kt           # Error types
├── models/
│   └── NetworkModels.kt          # DTOs
├── impl/
│   ├── KtorNetworkClient.kt      # Production impl
│   └── RetryPolicy.kt            # Retry logic
├── websocket/
│   └── WebSocketManager.kt       # WebSocket handling
├── mock/
│   └── MockNetworkClient.kt      # Mock impl
└── di/
    └── NetworkModule.kt          # Koin DI
```

## Dependencies

```kotlin
dependencies {
    api(project(":slate:core"))
    implementation(libs.bundles.ktor)  // HTTP + WebSocket
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
}
```
