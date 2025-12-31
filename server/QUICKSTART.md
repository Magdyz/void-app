# VOID Server - Quick Start Guide

## Prerequisites

1. **Redis** must be running:
   ```bash
   # macOS
   brew install redis
   brew services start redis

   # Or run manually
   redis-server
   ```

2. **Firebase service account** is already configured at:
   ```
   server/firebase-service-account.json
   ```

## Run the Server

### Option 1: Using Gradle (Development)

```bash
# From the root of the void-app directory
./gradlew :server:run
```

### Option 2: Build and Run JAR (Production)

```bash
# Build the server
./gradlew :server:build

# Run the JAR
java -jar server/build/libs/server-0.1.0.jar
```

### Option 3: Build Fat JAR

```bash
# Create a fat JAR with all dependencies
./gradlew :server:jar

# Run it
java -jar server/build/libs/server-0.1.0.jar
```

## Verify Server is Running

```bash
# Check health endpoint
curl http://localhost:8080/health

# Expected response:
{
  "status": "healthy",
  "redis": "connected",
  "firebase": "initialized"
}
```

## Test the Endpoints

### 1. Test Device Registration

```bash
curl -X POST http://localhost:8080/v1/device/register \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "test-account-id",
    "fcmToken": "test-fcm-token-12345",
    "signature": "test-signature",
    "timestamp": '$(date +%s)000'
  }'
```

### 2. Test Message Send

```bash
curl -X POST http://localhost:8080/v1/messages/send \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": "recipient-account-id",
    "encryptedPayload": "dGVzdCBlbmNyeXB0ZWQgZGF0YQ==",
    "senderId": "sender-account-id",
    "signature": "test-signature",
    "timestamp": '$(date +%s)000'
  }'
```

### 3. Test WebSocket Sync

```bash
# Install websocat if you don't have it
brew install websocat

# Connect to WebSocket
websocat ws://localhost:8080/v1/sync/stream \
  -H="Authorization: Bearer account-id:$(date +%s)000:signature"
```

## Update Android Client Configuration

Update the Android client to point to your local server:

1. Edit `slate/network/src/main/kotlin/com/void/slate/network/NetworkConfig.kt`
2. Update the DEBUG configuration:
   ```kotlin
   val DEBUG = NetworkConfig(
       serverUrl = "http://YOUR-LOCAL-IP:8080",  // Use your machine's IP
       enableWebSockets = true,
       mockMode = false
   )
   ```

3. For physical Android devices, use your machine's local IP:
   ```bash
   # Find your IP
   ifconfig | grep "inet " | grep -v 127.0.0.1
   ```

4. For Android emulator:
   ```bash
   # Use 10.0.2.2 (emulator's special alias for host machine)
   val DEBUG = NetworkConfig(
       serverUrl = "http://10.0.2.2:8080"
   )
   ```

## Configuration

Edit `server/src/main/resources/application.conf` to customize:

```conf
void {
    redis {
        host = "localhost"
        port = 6379
        tokenTtl = 2592000  # 30 days
        messageTtl = 604800  # 7 days
    }

    security {
        jitterMinMs = 50
        jitterMaxMs = 200
    }

    privacy {
        logIpAddresses = false  # KEEP THIS DISABLED
    }
}
```

## Environment Variables (Optional)

```bash
export REDIS_HOST=localhost
export REDIS_PORT=6379
export FIREBASE_SERVICE_ACCOUNT_PATH=server/firebase-service-account.json
export PORT=8080
```

## Troubleshooting

### Redis Connection Error

```bash
# Check if Redis is running
redis-cli ping
# Should return: PONG

# If not, start Redis
brew services start redis
```

### Firebase Initialization Error

```bash
# Verify the service account file exists
ls -la server/firebase-service-account.json

# Check it's valid JSON
cat server/firebase-service-account.json | python -m json.tool
```

### Port Already in Use

```bash
# Find what's using port 8080
lsof -i :8080

# Kill the process
kill -9 <PID>

# Or change the port in application.conf
```

## Logs

The server logs will show:

```
INFO  VOID Server started successfully on port 8080
INFO  Privacy mode: IP logging disabled
INFO  Timing jitter: 50-200ms
INFO  Redis connected to localhost:6379
INFO  Firebase Admin SDK initialized
```

## Next Steps

1. **Test with Android Client**: Build and run the Android app
2. **Register Device**: The app will automatically register its FCM token
3. **Send Test Messages**: Use the messaging feature in the app
4. **Monitor Logs**: Watch server logs to see tickles being sent

## Production Deployment

See `server/README.md` for detailed production deployment instructions including:
- Docker deployment
- Cloud hosting (AWS, GCP, DigitalOcean)
- SSL/TLS configuration
- Redis clustering
- Load balancing

---

**Questions?** Check `server/README.md` or the inline code comments.
