# Message Sync Debugging - Why Messages Aren't Being Received

## Issues Found

### 1. ❌ Wrong API Path (FIXED)
**Problem**: Client requests `/api/v1/messages` but server had routes at `/v1/messages`

**Solution**: Wrapped routes in `/api` prefix:
```kotlin
route("/api") {
    route("/v1") {
        deviceRoutes(redisService)
        messageRoutes(redisService, firebaseService)
        syncRoutes(redisService)
    }
}
```

**Status**: ✅ FIXED

---

### 2. ❌ Missing GET /messages Endpoint (FIXED)
**Problem**: Server only had WebSocket sync, not REST polling endpoint

**Solution**: Added `GET /api/v1/messages` endpoint in MessageRoutes.kt

**Status**: ✅ FIXED

---

### 3. ⚠️ Missing Authentication Headers (NEEDS CLIENT UPDATE)
**Problem**: Client doesn't send `X-Account-ID` header when fetching messages

**Current Behavior**:
- Client makes: `GET /api/v1/messages`
- Server expects: `X-Account-ID` header to know which user's messages to fetch
- Client doesn't send this header
- Server returns empty array (to avoid errors)

**Why This Matters**:
- Server stores messages per account ID in Redis
- Without account ID, server doesn't know whose messages to return
- Even if messages are queued, they can't be delivered

**Temporary Fix Applied**:
```kotlin
if (accountId == null) {
    // Return empty array instead of 401 error
    call.respond(HttpStatusCode.OK, emptyList<EncryptedMessage>())
    return@get
}
```

**Status**: ⚠️ WORKAROUND APPLIED - Client update needed

---

## How to Fix Message Reception

### Option 1: Add Authentication Header to Client (Recommended)

Update `KtorNetworkClient.kt` to send account ID header:

```kotlin
override suspend fun receiveMessages(since: Long?): Result<List<ReceivedMessage>> {
    return retryPolicy.executeWithRetry {
        executeRequest {
            val response = httpClient.get("${config.apiBaseUrl}/messages") {
                since?.let { parameter("since", it) }
                // ADD THIS:
                header("X-Account-ID", myAccountId)  // Need to get account ID from identity repository
            }
            response.body<List<ReceivedMessage>>()
        }
    }
}
```

**Challenges**:
- `KtorNetworkClient` needs access to user's account ID
- Could inject `IdentityRepository` or pass account ID during client initialization

---

### Option 2: Use WebSocket Sync Instead of REST Polling

The server implements proper authentication for WebSocket:
- `WS /api/v1/sync/stream` with `Authorization: Bearer <accountId>:<timestamp>:<signature>`
- Messages are pushed immediately
- Proper ACK mechanism

**Why Not Use This**:
- Client is currently using REST polling (`receiveMessages()`)
- Would require client refactoring to use WebSocket sync

---

### Option 3: Session-Based Authentication

Implement server-side sessions:
1. Client logs in once with signature
2. Server creates session and returns session cookie
3. Subsequent requests use session cookie
4. Server looks up account ID from session

**Pros**: Standard approach, no headers needed
**Cons**: More complex, requires session management

---

## Current Server Status

### Endpoints Available:
- ✅ `POST /api/v1/device/register` - Register FCM token
- ✅ `POST /api/v1/messages/send` - Send encrypted message
- ✅ `GET /api/v1/messages` - Fetch pending messages (needs auth header)
- ✅ `WS /api/v1/sync/stream` - WebSocket sync (has auth)

### What's Working:
- ✅ Messages can be sent
- ✅ Messages are queued in Redis
- ✅ Server returns 200 OK (no more 404 errors)

### What's NOT Working:
- ❌ Messages aren't retrieved because client doesn't send account ID
- ❌ No authentication on GET /messages endpoint

---

## Recommended Next Steps

1. **Quick Fix**: Update client to send `X-Account-ID` header
   - Inject `IdentityRepository` into `KtorNetworkClient`
   - Add header to GET /messages request
   - Test message reception

2. **Proper Fix**: Implement session-based auth or JWT
   - Client authenticates once with signature
   - Server returns session token/JWT
   - All requests include token
   - Server validates and extracts account ID

3. **Alternative**: Switch to WebSocket sync
   - Already has proper authentication
   - Real-time message delivery
   - Better for battery life (no polling)

---

## Testing

### Verify Server is Running:
```bash
curl http://192.168.1.26:8080/health
```

### Test GET /messages (Will Return Empty):
```bash
curl http://192.168.1.26:8080/api/v1/messages
```

### Test with Account ID Header:
```bash
curl http://192.168.1.26:8080/api/v1/messages \
  -H "X-Account-ID: your-account-id"
```

### Check Server Logs:
Server should show:
```
WARN: GET /messages: No X-Account-ID header, returning empty array
```

---

## Summary

**Root Cause**: Client and server authentication mismatch
- Server needs account ID to fetch messages
- Client doesn't send account ID
- Messages are queued but can't be delivered

**Immediate Impact**:
- No more 404 errors ✅
- But messages still won't be received ❌

**Required Action**:
Update client to send `X-Account-ID` header in `receiveMessages()` function

---

**Status**: Server is ready, client needs authentication update
