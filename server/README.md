# VOID Server - Privacy-First Messaging Backend

A zero-trust, privacy-first backend for the VOID messaging app. Implements "Payload-less Tickle" architecture to wake up devices without exposing metadata to Google/FCM.

## Core Philosophy

- **Zero Trust**: The server is a "dumb relay" that never knows message contents
- **Metadata Minimalism**: No social graphs, no IP logging, no long-term storage
- **Ephemeral Routing**: Data exists only to be routed and deleted

## Architecture

```
┌─────────────┐                    ┌─────────────┐
│   User A    │                    │   User B    │
│   Device    │                    │   Device    │
└──────┬──────┘                    └──────▲──────┘
       │                                  │
       │ 1. Send encrypted msg            │ 6. Decrypt locally
       │                                  │
       ▼                                  │
┌─────────────────────────────────────────┴──────┐
│              VOID Server                       │
│  ┌──────────────────┐  ┌─────────────────┐   │
│  │ Redis Queue      │  │  FCM Token Map  │   │
│  │ (TTL: 7 days)    │  │  (TTL: 30 days) │   │
│  └────────┬─────────┘  └────────┬────────┘   │
│           │ 2. Store             │ 3. Lookup  │
└───────────┼──────────────────────┼────────────┘
            │                      │
            │                      ▼
            │            ┌──────────────────────┐
            │            │  Google FCM Service  │
            │            │  (Sends EMPTY ping)  │
            │            └──────────┬───────────┘
            │                       │ 4. Empty tickle
            │                       ▼
            │            ┌──────────────────────┐
            │            │  Android Client      │
            │            │  (Wake up)           │
            │            └──────────┬───────────┘
            │                       │ 5. WebSocket sync
            └───────────────────────┘
```

## Tech Stack

- **Framework**: Ktor 2.3.7 (Kotlin)
- **Database**: Redis (ephemeral storage only)
- **Push**: Firebase Admin SDK
- **Cryptography**: BouncyCastle (Ed25519 signature verification)

## API Endpoints

### 1. Device Registration

**Endpoint**: `POST /v1/device/register`

Registers a device's FCM token.

**Request**:
```json
{
  "accountId": "<base64-public-key>",
  "fcmToken": "<firebase-token>",
  "signature": "<base64-signature>",
  "timestamp": 1234567890
}
```

**Response**:
```json
{
  "success": true,
  "message": "Device registered successfully"
}
```

**Privacy Constraints**:
- No IP logging
- No linking to other identity data
- Overwrites previous token silently

### 2. Send Message (with FCM Tickle)

**Endpoint**: `POST /v1/messages/send`

Sends an encrypted message and triggers FCM tickle.

**Request**:
```json
{
  "recipientId": "<recipient-public-key>",
  "encryptedPayload": "<base64-encrypted-blob>",
  "senderId": "<sender-public-key>",
  "signature": "<base64-signature>",
  "timestamp": 1234567890
}
```

**Response**:
```json
{
  "success": true,
  "messageId": "uuid-v4",
  "message": "Message queued for delivery"
}
```

**FCM Payload** (CRITICAL):
```json
{
  "data": {
    "type": "check_server"
  }
}
```

**FORBIDDEN**: Including `sender_id`, `preview`, or `message_id` in FCM payload.

### 3. WebSocket Sync Stream

**Endpoint**: `WS /v1/sync/stream`

Real-time encrypted message synchronization.

**Authentication**:
```
Authorization: Bearer <accountId>:<timestamp>:<signature>
```

**Flow**:
1. Client connects with signed JWT
2. Server pushes all pending messages
3. Client sends ACK for each message
4. Server deletes message upon ACK
5. Connection maintained for real-time delivery

**Client ACK**:
```json
{
  "messageId": "uuid-v4",
  "status": "received"
}
```

## Setup Instructions

### Prerequisites

1. **Java 17+**
   ```bash
   java -version
   ```

2. **Redis**
   ```bash
   # macOS
   brew install redis
   brew services start redis

   # Ubuntu/Debian
   sudo apt install redis-server
   sudo systemctl start redis
   ```

3. **Firebase Service Account**
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Select your project (or create one)
   - Go to Project Settings → Service Accounts
   - Click "Generate New Private Key"
   - Save as `server/firebase-service-account.json`

### Configuration

1. **Place Firebase Service Account**
   ```bash
   cp ~/Downloads/your-firebase-key.json server/firebase-service-account.json
   ```

2. **Environment Variables** (optional)
   ```bash
   export REDIS_HOST=localhost
   export REDIS_PORT=6379
   export FIREBASE_SERVICE_ACCOUNT_PATH=firebase-service-account.json
   ```

3. **Edit `application.conf`** (if needed)
   ```
   server/src/main/resources/application.conf
   ```

### Build & Run

1. **Build the server**
   ```bash
   cd server
   ../gradlew build
   ```

2. **Run the server**
   ```bash
   ../gradlew run
   ```

   Or run the JAR directly:
   ```bash
   java -jar build/libs/server-0.1.0.jar
   ```

3. **Verify it's running**
   ```bash
   curl http://localhost:8080/health
   ```

   Expected response:
   ```json
   {
     "status": "healthy",
     "redis": "connected",
     "firebase": "initialized"
   }
   ```

### Docker Deployment (Optional)

1. **Create Dockerfile**
   ```dockerfile
   FROM openjdk:17-slim
   WORKDIR /app
   COPY build/libs/server-0.1.0.jar app.jar
   COPY firebase-service-account.json .
   EXPOSE 8080
   CMD ["java", "-jar", "app.jar"]
   ```

2. **Build Docker image**
   ```bash
   docker build -t void-server .
   ```

3. **Run with Docker Compose**
   ```yaml
   version: '3.8'
   services:
     redis:
       image: redis:7-alpine
       ports:
         - "6379:6379"

     void-server:
       image: void-server
       ports:
         - "8080:8080"
       environment:
         - REDIS_HOST=redis
         - REDIS_PORT=6379
       depends_on:
         - redis
   ```

## Testing

### Test Device Registration

```bash
curl -X POST http://localhost:8080/v1/device/register \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "test-public-key",
    "fcmToken": "test-fcm-token",
    "signature": "test-signature",
    "timestamp": '$(date +%s)'000'
  }'
```

### Test Message Send

```bash
curl -X POST http://localhost:8080/v1/messages/send \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": "recipient-public-key",
    "encryptedPayload": "base64-encrypted-data",
    "senderId": "sender-public-key",
    "signature": "test-signature",
    "timestamp": '$(date +%s)'000'
  }'
```

### Test WebSocket Sync

```bash
# Using websocat
websocat ws://localhost:8080/v1/sync/stream \
  -H="Authorization: Bearer account-id:timestamp:signature"
```

## Security Features

### 1. Signature Verification

All requests must be signed using Ed25519:
- Public key = `accountId`
- Signature = `sign(timestamp, privateKey)`
- Timeout: 60 seconds

### 2. Timing Attack Mitigation

Random jitter (50-200ms) on all responses to blur timing correlations.

### 3. Generic Error Messages

All errors return:
```json
{
  "error": "Unauthorized",
  "code": 401
}
```

No verbose messages that could help attackers enumerate accounts.

### 4. Privacy-First Logging

- IP addresses are NOT logged
- Only generic event logs (no metadata)
- Configurable via `void.privacy.logIpAddresses = false`

## Production Deployment

### Recommended Infrastructure

1. **Load Balancer**: NGINX or AWS ALB
2. **Redis**: Managed Redis (AWS ElastiCache, Redis Cloud)
3. **Compute**: Kubernetes, AWS ECS, or DigitalOcean App Platform
4. **SSL/TLS**: Let's Encrypt or AWS Certificate Manager

### Environment Variables (Production)

```bash
PORT=8080
REDIS_HOST=your-redis-host.amazonaws.com
REDIS_PORT=6379
REDIS_PASSWORD=your-redis-password
FIREBASE_SERVICE_ACCOUNT_PATH=/secrets/firebase-key.json
```

### Scaling Considerations

- **Horizontal Scaling**: Stateless design allows multiple instances
- **Redis Clustering**: Use Redis Cluster for high availability
- **WebSocket Sticky Sessions**: Use load balancer sticky sessions

## Privacy Guarantees

### What the Server CANNOT See

- Message content (encrypted end-to-end)
- Social graphs (no contact lists stored)
- User IP addresses (not logged)
- Message metadata (only encrypted blobs)

### What the Server CAN See

- Encrypted message blobs (but cannot decrypt)
- FCM tokens (anonymous, TTL-based)
- Approximate message timing

### Mitigation Strategies

1. **Timing Jitter**: Random delays blur message timing
2. **Ephemeral Storage**: Auto-delete via Redis TTL
3. **No Persistent Logs**: Minimal logging, no IP storage
4. **Decoy Tickles**: Optional random FCM pings (disabled by default)

## Troubleshooting

### Redis Connection Failed

```bash
# Check if Redis is running
redis-cli ping
# Should return: PONG

# Check Redis logs
tail -f /var/log/redis/redis-server.log
```

### Firebase Initialization Failed

```bash
# Verify service account JSON exists
ls -la server/firebase-service-account.json

# Check JSON format
cat server/firebase-service-account.json | jq .
```

### WebSocket Auth Failed

- Ensure timestamp is recent (<60 seconds)
- Verify signature is Base64-encoded
- Check token format: `accountId:timestamp:signature`

## Contributing

1. All code changes must pass privacy review
2. No logging of IP addresses or metadata
3. All errors must use generic messages
4. Follow zero-trust principles

## License

MIT License - See LICENSE file

---

**Questions?** Open an issue or check the inline code comments.
