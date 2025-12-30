# Push Notifications & Deep Links Setup Guide

This document explains the privacy-first push notification system and deep linking implementation for VOID.

## 🏗️ Architecture Overview

### The "Tickle" Architecture (Payload-less FCM)

VOID implements a **privacy-first push notification system** where:
1. ✅ **NO message content passes through Google's servers**
2. ✅ **FCM only sends empty "wake-up" notifications**
3. ✅ **All message content is fetched via secure WebSocket**
4. ✅ **Decryption happens locally on the device**

```
┌─────────────┐                    ┌─────────────┐
│   User A    │                    │   User B    │
│   Device    │                    │   Device    │
└──────┬──────┘                    └──────▲──────┘
       │                                  │
       │ 1. Send encrypted msg            │ 6. Decrypt locally
       │                                  │    Post notification
       ▼                                  │
┌─────────────────────────────────────────┴──────┐
│              VOID Server                       │
│  ┌──────────────────┐  ┌─────────────────┐   │
│  │ Encrypted Blob   │  │  FCM Token Map  │   │
│  │ Storage          │  │  (anonymous)    │   │
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
            │            │  VoidFirebaseService │
            │            │  (Wake up worker)    │
            │            └──────────┬───────────┘
            │                       │ 5. Trigger sync
            │                       ▼
            │            ┌──────────────────────┐
            │            │  MessageSyncWorker   │
            └────────────┤  (Fetch & decrypt)   │
                         └──────────────────────┘
```

## 📦 What Was Implemented

### 1. Build Flavors (Play Store vs F-Droid)

**File:** `app/build.gradle.kts`

```kotlin
flavorDimensions += "store"
productFlavors {
    create("play") {
        dimension = "store"
        // Uses Firebase Cloud Messaging
    }
    create("foss") {
        dimension = "store"
        // Uses UnifiedPush (stub for now)
        applicationIdSuffix = ".foss"
    }
}
```

**Build variants:**
- `playDebug` / `playRelease` - Play Store builds with FCM
- `fossDebug` / `fossRelease` - F-Droid builds with UnifiedPush (stub)

### 2. MessageSyncEngine

**File:** `blocks/messaging/src/main/kotlin/com/void/block/messaging/sync/MessageSyncEngine.kt`

**Responsibilities:**
- Maintain WebSocket connection to VOID server
- Receive encrypted message blobs
- Decrypt messages locally using Signal Protocol
- Store messages in local encrypted database
- Post generic notifications (privacy-preserving)

**Modes:**
- **One-time sync:** Connect → Fetch → Decrypt → Disconnect (used by WorkManager)
- **Persistent sync:** 24/7 WebSocket connection (used in Hostile Mode)

**Key methods:**
- `performOneTimeSync()` - Called by WorkManager after FCM tickle
- `startPersistentSync()` - For Hostile Mode (always-on connection)
- `enableHostileMode()` - Promotes to foreground service

### 3. MessageSyncWorker

**File:** `blocks/messaging/src/main/kotlin/com/void/block/messaging/sync/MessageSyncWorker.kt`

**Purpose:** WorkManager worker triggered by FCM tickle

**Flow:**
1. FCM sends empty notification
2. VoidFirebaseService catches it
3. Enqueues MessageSyncWorker
4. Worker calls `MessageSyncEngine.performOneTimeSync()`
5. Worker completes (Android can kill process)

**Features:**
- Guaranteed execution (even if app is killed)
- Expedited work for fast delivery (Android 12+)
- Automatic retry with backoff (up to 3 attempts)

### 4. VoidFirebaseService (Play Flavor)

**File:** `app/src/play/kotlin/com/void/app/service/VoidFirebaseService.kt`

**Critical Privacy Features:**
- ⚠️ Validates that FCM payload is EMPTY
- ⚠️ Logs warning if non-empty payload received
- 🚨 Does NOT process message content

**Key methods:**
- `onNewToken()` - Send FCM token to VOID server
- `onMessageReceived()` - Trigger MessageSyncWorker
- `onDeletedMessages()` - Recover from missed messages

### 5. Deep Links

**Files:**
- `app/src/main/AndroidManifest.xml` - Intent filters
- `app/src/main/kotlin/com/void/app/MainActivity.kt` - Deep link handler

**Supported formats:**
- `void://ghost.paper.forty` - Custom scheme
- `https://void.chat/c/ghost.paper.forty` - App link (verified)

**Behavior:**
- Opens VOID app
- Navigates to "Add Contact" screen
- Pre-fills the 3-word identity
- User can review and accept

## 🔧 Setup Instructions

### Step 1: Firebase Setup (Play Flavor Only)

1. **Create Firebase Project**
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Create new project (or use existing)
   - Add Android app with package name: `app.voidapp.secure`

2. **Download google-services.json**
   ```bash
   # Place in app/ directory (NOT app/src/play/)
   cp ~/Downloads/google-services.json app/google-services.json
   ```

3. **Enable Google Services Plugin**

   Edit `app/build.gradle.kts`:
   ```kotlin
   plugins {
       // ... existing plugins
       alias(libs.plugins.google.services) // Change from "apply false" to enabled
   }
   ```

4. **Configure Cloud Messaging**
   - In Firebase Console → Cloud Messaging
   - Note your Server Key (for VOID server)

### Step 2: Build & Test

**Build Play flavor:**
```bash
./gradlew assemblePlayDebug
```

**Build FOSS flavor:**
```bash
./gradlew assembleFossDebug
```

**Install on device:**
```bash
adb install app/build/outputs/apk/play/debug/app-play-debug.apk
```

### Step 3: Test Push Notifications

**Test FCM token generation:**
1. Install app on device
2. Check logcat for: `🔑 New FCM token generated`
3. Token should appear in logs

**Test deep links:**

```bash
# Test void:// scheme
adb shell am start -a android.intent.action.VIEW -d "void://ghost.paper.forty"

# Test https:// app link
adb shell am start -a android.intent.action.VIEW -d "https://void.chat/c/ghost.paper.forty"
```

**Test push notification flow:**
1. Use Firebase Console → Cloud Messaging → Send test message
2. Send to specific FCM token
3. Leave payload EMPTY
4. Check logcat for sync flow

### Step 4: Server Integration

**TODO items for server team:**

1. **FCM Token Registration Endpoint**
   ```
   POST /api/v1/register-push-token
   Headers:
     X-Account-ID: <user's 3-word identity>
     X-Signature: <signed request using identity keys>
   Body:
     {
       "fcm_token": "...",
       "platform": "android"
     }
   ```

2. **Send Empty FCM Tickle**

   When a message arrives for a user:
   ```json
   {
     "to": "<user_fcm_token>",
     "data": {
       "type": "check_server"
     },
     "priority": "high"
   }
   ```

   ⚠️ **CRITICAL:** Do NOT include message content in FCM payload!

3. **WebSocket Message Fetch Endpoint**

   MessageSyncEngine will call:
   ```
   GET /api/v1/messages?since=<timestamp>
   Headers:
     X-Account-ID: <identity>
     X-Signature: <signed request>
   Response:
     [
       {
         "sender_id": "ghost.paper.forty",
         "encrypted_payload": "<base64_encrypted_blob>",
         "timestamp": 1234567890
       }
     ]
   ```

## 🧪 Testing Checklist

- [ ] **Build succeeds** for both play and foss flavors
- [ ] **FCM token** is generated on first launch (play flavor)
- [ ] **Deep link** `void://...` opens app and navigates correctly
- [ ] **App link** `https://void.chat/c/...` opens app
- [ ] **Identity validation** rejects invalid formats
- [ ] **WorkManager** enqueues successfully after FCM tickle
- [ ] **MessageSyncEngine** connects to server (once server is ready)
- [ ] **Notifications** are posted with generic content

## 📋 TODO Items

### High Priority

1. **Server Implementation**
   - [ ] Implement FCM token registration endpoint
   - [ ] Implement message fetch endpoint (WebSocket or REST)
   - [ ] Configure FCM server key in server environment

2. **Token Registration**
   - [ ] Implement `VoidFirebaseService.onNewToken()` API call
   - [ ] Add retry logic for token registration
   - [ ] Handle token refresh

3. **MessageRepository Integration**
   - [ ] Verify `receiveMessage()` method exists
   - [ ] Ensure MessageRepository stores messages correctly
   - [ ] Add notification click handling (open specific chat)

### Medium Priority

4. **Error Handling**
   - [ ] Add offline support (queue messages)
   - [ ] Handle network errors gracefully
   - [ ] Add user-facing error messages

5. **Hostile Mode UI**
   - [ ] Add Settings screen
   - [ ] Add "Hostile Mode" toggle
   - [ ] Show foreground service notification when enabled

6. **App Links Verification**
   - [ ] Create `.well-known/assetlinks.json` on void.chat domain
   - [ ] Configure for automatic verification

### Low Priority

7. **UnifiedPush (FOSS Flavor)**
   - [ ] Implement UnifiedPush integration
   - [ ] Add distributor app detection
   - [ ] Test with ntfy, NextPush, etc.

8. **Optimization**
   - [ ] Add notification channels (categories)
   - [ ] Implement notification grouping
   - [ ] Add custom notification icons
   - [ ] Optimize battery usage

## 🔐 Security Considerations

### Privacy Guarantees

✅ **What Google CAN'T see:**
- Message content (encrypted end-to-end)
- Sender identity (only knows "some server sent a tickle")
- Recipient identity (only knows FCM token, not linked to real identity)
- Message metadata (timestamp, length, etc.)

⚠️ **What Google CAN see:**
- Device FCM token
- When a tickle is sent to the device
- Approximate timing of messages (but not content)

### Mitigation Strategies

1. **Random Delays:** Server can add random delays before sending tickles
2. **Batching:** Send tickles in batches to hide message timing
3. **Hostile Mode:** Users in high-risk regions can disable FCM entirely
4. **Decoy Tickles:** Server can send random tickles to create noise

## 📚 Reference

### Key Files

```
void-app/
├── app/
│   ├── build.gradle.kts                     # Flavor configuration
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml          # Deep link intents
│   │   │   └── kotlin/com/void/app/
│   │   │       ├── MainActivity.kt          # Deep link handler
│   │   │       └── di/AppModule.kt          # Koin DI config
│   │   ├── play/
│   │   │   ├── AndroidManifest.xml          # FCM service registration
│   │   │   └── kotlin/com/void/app/service/
│   │   │       └── VoidFirebaseService.kt   # FCM receiver
│   │   └── foss/
│   │       ├── AndroidManifest.xml          # FOSS flavor (stub)
│   │       └── kotlin/com/void/app/service/
│   │           └── VoidPushService.kt       # UnifiedPush (stub)
│   └── google-services.json                 # ⚠️ ADD THIS FILE
└── blocks/messaging/src/main/kotlin/com/void/block/messaging/
    └── sync/
        ├── MessageSyncEngine.kt             # Sync engine
        └── MessageSyncWorker.kt             # WorkManager worker
```

### Dependencies Added

```toml
firebase-bom = "33.7.0"
workmanager = "2.9.1"
unifiedpush = "2.4.0"
google-services = "4.4.2"
```

## 🎯 Summary

**Implemented:**
✅ Payload-less FCM push notifications (Play flavor)
✅ MessageSyncEngine with WebSocket support
✅ WorkManager integration for guaranteed delivery
✅ Deep links (void:// and https://void.chat/c/)
✅ Privacy-preserving notification system
✅ Hostile Mode architecture (Settings UI pending)
✅ FOSS flavor stub (UnifiedPush pending)

**Next Steps:**
1. Add `google-services.json` to app/ directory
2. Implement server endpoints for token registration and message fetch
3. Test end-to-end flow with real Firebase account
4. Implement Hostile Mode Settings UI
5. Complete UnifiedPush integration for F-Droid

---

**Questions?** Check the inline code comments or see the architectural diagram above.
