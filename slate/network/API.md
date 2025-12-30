# VOID Network API Specification

API specification for the VOID relay server.

**Version**: v1
**Base URL**: `https://void-relay.example.com/v1`
**WebSocket**: `wss://void-relay.example.com/ws`

## Overview

The VOID relay server is a **transport layer only**. It:
- Routes E2E encrypted messages between users
- Stores messages temporarily until delivered
- Facilitates contact discovery
- Does NOT see plaintext content (E2E encrypted)

## Authentication

**Phase 3**: No authentication (MVP)
**Phase 4**: Identity-based authentication using Ed25519 signatures

## Endpoints

### 1. Send Message

Send an encrypted message to a recipient.

**Endpoint**: `POST /v1/messages/send`

**Request**:
```json
{
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "recipientIdentity": "alpha.beta.gamma",
  "encryptedPayload": [1, 2, 3, ...],
  "timestamp": 1704067200000
}
```

**Response** (200 OK):
```json
{
  "success": true,
  "serverTimestamp": 1704067201000,
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "error": null
}
```

**Response** (400 Bad Request):
```json
{
  "success": false,
  "serverTimestamp": 1704067201000,
  "messageId": null,
  "error": "Invalid recipient identity"
}
```

**Response** (429 Too Many Requests):
```json
{
  "success": false,
  "error": "Rate limit exceeded",
  "retryAfter": 60000
}
```

---

### 2. Receive Messages (Polling)

Retrieve messages sent to the authenticated user.

**Endpoint**: `GET /v1/messages`

**Query Parameters**:
- `since` (optional): Unix timestamp (ms) - only return messages after this time

**Request**:
```
GET /v1/messages?since=1704067200000
```

**Response** (200 OK):
```json
[
  {
    "messageId": "550e8400-e29b-41d4-a716-446655440000",
    "senderIdentity": "delta.echo.foxtrot",
    "encryptedPayload": [1, 2, 3, ...],
    "serverTimestamp": 1704067201000
  },
  {
    "messageId": "660e8400-e29b-41d4-a716-446655440001",
    "senderIdentity": "golf.hotel.india",
    "encryptedPayload": [4, 5, 6, ...],
    "serverTimestamp": 1704067202000
  }
]
```

**Response** (200 OK - No messages):
```json
[]
```

---

### 3. WebSocket Stream (Real-time)

Receive messages in real-time via WebSocket.

**Endpoint**: `ws://void-relay.example.com/ws`

**Protocol**:
1. Client connects to WebSocket
2. Server sends `ReceivedMessage` JSON objects as they arrive
3. Client processes each message

**Message Format**:
```json
{
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "senderIdentity": "delta.echo.foxtrot",
  "encryptedPayload": [1, 2, 3, ...],
  "serverTimestamp": 1704067201000
}
```

**Connection Management**:
- Ping interval: 30 seconds
- Idle timeout: 5 minutes
- Reconnect on disconnect with exponential backoff

---

### 4. Send Contact Request

Send a contact request to another user.

**Endpoint**: `POST /v1/contacts/request`

**Request**:
```json
{
  "requestId": "770e8400-e29b-41d4-a716-446655440002",
  "fromIdentity": "alpha.beta.gamma",
  "toIdentity": "delta.echo.foxtrot",
  "publicKeyBundle": [10, 20, 30, ...],
  "timestamp": 1704067200000
}
```

**Public Key Bundle Format**:
- Bytes 0-31: X25519 public key (encryption)
- Bytes 32-63: Ed25519 public key (signing)

**Response** (200 OK):
```json
{
  "success": true,
  "accepted": false,
  "contactId": null,
  "error": null
}
```

**Response** (400 Bad Request):
```json
{
  "success": false,
  "accepted": false,
  "contactId": null,
  "error": "Invalid identity format"
}
```

---

### 5. Poll Contact Requests

Retrieve pending contact requests.

**Endpoint**: `GET /v1/contacts/requests`

**Response** (200 OK):
```json
[
  {
    "requestId": "770e8400-e29b-41d4-a716-446655440002",
    "fromIdentity": "golf.hotel.india",
    "toIdentity": "alpha.beta.gamma",
    "publicKeyBundle": [10, 20, 30, ...],
    "timestamp": 1704067200000
  }
]
```

**Response** (200 OK - No requests):
```json
[]
```

---

## Data Models

### MessageSendRequest
```kotlin
data class MessageSendRequest(
    val messageId: String,              // UUID
    val recipientIdentity: String,      // "word1.word2.word3"
    val encryptedPayload: ByteArray,    // E2E encrypted message
    val timestamp: Long                 // Unix timestamp (ms)
)
```

### MessageSendResponse
```kotlin
data class MessageSendResponse(
    val success: Boolean,
    val serverTimestamp: Long,
    val messageId: String?,
    val error: String?
)
```

### ReceivedMessage
```kotlin
data class ReceivedMessage(
    val messageId: String,
    val senderIdentity: String,
    val encryptedPayload: ByteArray,
    val serverTimestamp: Long
)
```

### ContactExchangeRequest
```kotlin
data class ContactExchangeRequest(
    val requestId: String,
    val fromIdentity: String,
    val toIdentity: String,
    val publicKeyBundle: ByteArray,     // X25519 + Ed25519 keys
    val timestamp: Long
)
```

### ContactExchangeResponse
```kotlin
data class ContactExchangeResponse(
    val success: Boolean,
    val accepted: Boolean,              // Will be true when recipient accepts
    val contactId: String?,
    val error: String?
)
```

---

## Error Codes

| Code | Meaning | Retry? |
|------|---------|--------|
| 200 | Success | N/A |
| 400 | Bad Request (validation error) | No |
| 401 | Unauthorized (invalid auth) | No |
| 429 | Rate Limit Exceeded | Yes (after delay) |
| 500 | Internal Server Error | Yes |
| 503 | Service Unavailable | Yes |

---

## Rate Limits

**Phase 3 (MVP)**:
- No rate limiting

**Phase 4**:
- Messages: 100 per minute per user
- Contact requests: 10 per hour per user
- Polling: 60 requests per minute

Rate limit headers:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1704067260
```

---

## Message Retention

- **Undelivered messages**: 30 days
- **Delivered messages**: Deleted immediately
- **Contact requests**: 7 days

---

## Security

### Transport Security
- **TLS 1.3** required for all connections
- Certificate pinning recommended for clients

### End-to-End Encryption
- Server **never** sees plaintext content
- All messages encrypted with Signal Protocol (Double Ratchet)
- Public key bundles are **public** (used for initial key exchange)

### Metadata
⚠️ **Server can see**:
- Sender and recipient identities
- Message timestamps
- Message sizes
- IP addresses (connection metadata)

**Phase 4** will add metadata protection via:
- Onion routing
- Traffic padding
- Timing obfuscation

---

## WebSocket Protocol Details

### Connection Flow

1. **Connect**
   ```
   Client → Server: WebSocket handshake
   Server → Client: 101 Switching Protocols
   ```

2. **Authentication** (Phase 4)
   ```
   Client → Server: { "type": "auth", "identity": "...", "signature": "..." }
   Server → Client: { "type": "auth_success" }
   ```

3. **Receive Messages**
   ```
   Server → Client: { "messageId": "...", "senderIdentity": "...", ... }
   Server → Client: { "messageId": "...", "senderIdentity": "...", ... }
   ```

4. **Keep-Alive**
   ```
   Server → Client: Ping (every 30s)
   Client → Server: Pong
   ```

5. **Disconnect**
   ```
   Client → Server: Close
   Server → Client: Close acknowledgment
   ```

### Error Messages

```json
{
  "type": "error",
  "code": "INVALID_MESSAGE",
  "message": "Failed to parse message"
}
```

---

## Example Client Implementation

### Kotlin (using Ktor)

```kotlin
val client = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json()
    }
    install(WebSockets)
}

// Send message
val response = client.post("https://void-relay.example.com/v1/messages/send") {
    contentType(ContentType.Application.Json)
    setBody(MessageSendRequest(...))
}

// Receive via WebSocket
client.webSocket("wss://void-relay.example.com/ws") {
    for (frame in incoming) {
        val message = Json.decodeFromString<ReceivedMessage>(frame.readText())
        handleMessage(message)
    }
}
```

---

## Server Implementation Notes

**Phase 3** server requirements:
- HTTP/2 support
- WebSocket support
- JSON serialization
- Message queue (temporary storage)
- Identity-based routing

**Recommended Stack**:
- Language: Kotlin, Go, or Rust
- Framework: Ktor, Gin, or Actix
- Database: PostgreSQL or MongoDB
- Queue: Redis or RabbitMQ
- Deployment: Kubernetes

---

## Future API Extensions

### Phase 4: Privacy
- `POST /v1/messages/send/anonymous` - Send via onion routing
- Header: `X-Onion-Route: layer1,layer2,layer3`

### Phase 5: P2P
- `GET /v1/peers/discover` - Discover nearby peers
- `POST /v1/peers/announce` - Announce P2P availability

### Phase 6: Groups
- `POST /v1/groups/create` - Create group chat
- `POST /v1/groups/{id}/send` - Send to group

---

## Testing

### Mock Server Endpoints

For local development, use `MockNetworkClient` which simulates:
- 200ms latency
- Message loopback (sent messages appear as received)
- In-memory storage
- No actual network calls

### Test Credentials

**Phase 3**: No authentication
**Phase 4**: Test identities will be provided
