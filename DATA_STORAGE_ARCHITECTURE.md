# VOID Data Storage Architecture

**Last Updated:** 2026-01-05

This document explains where all data is stored in the VOID architecture and what is shared with Google/Firebase.

---

## 🎯 High-Level Summary

### Messages are Stored in THREE Places:

1. **Mobile Device (Encrypted)** - Permanent storage after decryption ✅
2. **Supabase Database (Encrypted)** - Temporary queue (max 7 days) ✅
3. **Google/Firebase** - **NOTHING** (only wake signals, zero message data) ✅

---

## 📱 1. Mobile Device Storage (Client-Side)

### What's Stored ON YOUR PHONE:

**Location:** Android encrypted storage (SQLCipher database)
**Encryption:** Android Keystore + SQLCipher (AES-256)
**Lifetime:** Permanent (until user deletes)

#### Messages (Full History)
```kotlin
// MessageRepository.kt stores:
storage.put("message.{message_id}", {
  id: UUID
  conversationId: UUID
  senderId: UUID (contact ID)
  recipientId: "me"
  content: "Decrypted plaintext message"  ← ONLY on device!
  timestamp: Unix timestamp
  status: SENT | DELIVERED | READ
  direction: INCOMING | OUTGOING
  encryptedPayload: ByteArray (original ciphertext for verification)
})
```

**What This Means:**
- ✅ **Full message history** stored on your phone
- ✅ **Decrypted plaintext** (only you can read)
- ✅ **Encrypted at rest** (SQLCipher + Android Keystore)
- ✅ **Never leaves your device** after decryption
- ✅ **Survives app restarts**
- ❌ **NOT synced to cloud** (no iCloud, no Google Drive)

#### Conversations (Metadata)
```kotlin
storage.put("conversation.{conversation_id}", {
  id: UUID (contact ID)
  contactId: UUID
  lastMessage: Message (full message object)
  lastMessageAt: timestamp
  unreadCount: number
})
```

#### Contacts (Your Address Book)
```kotlin
storage.put("contact.{contact_id}", {
  id: UUID
  displayName: "Alice"
  threeWordId: "ghost.paper.forty"
  publicKey: ByteArray (X25519 public key)
  addedAt: timestamp
})
```

#### Your Identity (Your Keys)
```kotlin
storage.put("identity.seed", {
  seed: ByteArray (32 bytes - MASTER SECRET)
  threeWordId: "clever.river.moon"
  publicKey: ByteArray (X25519 public key)
  privateKey: ByteArray (X25519 private key - NEVER leaves device)
  createdAt: timestamp
})
```

**CRITICAL:**
- 🔑 Your **private key NEVER leaves your device**
- 🔑 Your **identity seed NEVER leaves your device**
- 🔑 Only **public keys** are shared with contacts (via QR code/deep link)

---

## ☁️ 2. Supabase Database (Server-Side)

### What's Stored on SUPABASE (Self-Hosted PostgreSQL):

**Location:** Your Supabase project (you control this server)
**Encryption:** E2E encrypted payloads (server cannot decrypt)
**Lifetime:** Temporary (max 7 days, deleted after fetch)

### Table 1: `message_queue` (Temporary Message Transit)

```sql
CREATE TABLE message_queue (
  id UUID PRIMARY KEY,
  mailbox_hash TEXT NOT NULL,  -- SHA-256(recipient_seed + epoch) - BLIND!
  ciphertext TEXT NOT NULL,    -- Base64 encoded E2E encrypted blob
  epoch BIGINT NOT NULL,       -- Unix timestamp (seconds)
  expires_at TIMESTAMPTZ,      -- Auto-delete after 7 days
  created_at TIMESTAMPTZ
);
```

**What This Contains:**
- ✅ **Encrypted message blob** (server CANNOT decrypt - no keys!)
- ✅ **Blind mailbox hash** (server doesn't know WHO the recipient is)
- ✅ **Epoch timestamp** (for mailbox rotation, not message timing)
- ❌ **NO sender identity** (sealed sender protocol)
- ❌ **NO recipient identity** (blind mailbox)
- ❌ **NO plaintext content** (E2E encrypted)
- ❌ **NO message metadata** (counts, read status, etc.)

**What the Ciphertext Contains (E2E Encrypted):**
```
Encrypted blob = Encrypt({
  sender_public_key: 32 bytes (unsealed by recipient)
  message_content: "Hello, Alice!"
  timestamp: Unix timestamp
  nonce: random bytes
}, recipient_public_key)
```

**Server's View:**
```
Server sees:
- Mailbox "abc123..." received blob "xj2k9..." at epoch 1704067200
- NO IDEA who sender is
- NO IDEA who recipient is (mailbox is just a hash)
- NO IDEA what message says (encrypted)
```

**Privacy Properties:**
- 🔒 Server cannot read messages (no decryption keys)
- 🔒 Server doesn't know recipient identity (blind mailbox hash)
- 🔒 Server doesn't know sender identity (sealed sender)
- 🔒 Messages auto-delete after fetch (no server-side history)
- 🔒 Messages expire after 7 days (TTL cleanup)

### Table 2: `push_registrations` (FCM Token Mapping)

```sql
CREATE TABLE push_registrations (
  mailbox_hash TEXT PRIMARY KEY,        -- Blind mailbox (rotates daily)
  fcm_token TEXT NOT NULL,              -- Google FCM device token
  next_heartbeat_at TIMESTAMPTZ,        -- When to send next heartbeat
  heartbeat_interval_seconds INT,       -- Poisson interval (600-1200s)
  expires_at TIMESTAMPTZ,               -- Auto-delete after 25 hours
  created_at TIMESTAMPTZ
);
```

**What This Contains:**
- ✅ **Mailbox hash** (blind - server doesn't know identity)
- ✅ **FCM token** (Google's device token for push)
- ✅ **Heartbeat schedule** (Poisson distribution timing)
- ❌ **NO user identity** (mailbox hash rotates daily)
- ❌ **NO long-term tracking** (expires after 25 hours)

**Privacy Properties:**
- 🔒 Server cannot link mailbox to user (hash rotates daily)
- 🔒 FCM token is only device identifier (not linked to identity)
- 🔒 Heartbeat timing is per-device, not per-user
- 🔒 Registration expires after 25 hours (no long-term tracking)

### Table 3: `ephemeral_tokens` (Authentication Tokens)

```sql
CREATE TABLE ephemeral_tokens (
  id UUID PRIMARY KEY,
  mailbox_hash TEXT NOT NULL,          -- Blind mailbox
  challenge_hash TEXT NOT NULL,        -- SHA-256 of HMAC proof
  created_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,              -- Expires after 10 minutes
  used BOOLEAN,                        -- Single-use tokens
  used_at TIMESTAMPTZ
);
```

**What This Contains:**
- ✅ **Proof of mailbox ownership** (HMAC challenge)
- ✅ **Short-lived tokens** (10 minutes, single-use)
- ❌ **NO identity seed** (only hash of proof)
- ❌ **NO long-term tracking** (auto-deleted after expiry)

**Privacy Properties:**
- 🔒 Server never sees identity seed (only HMAC proof)
- 🔒 Tokens are ephemeral (10 min lifetime)
- 🔒 Single-use only (invalidated after fetch)
- 🔒 Auto-deleted after expiry (no permanent record)

### Table 4: `rate_limit_tracker` (Spam Prevention)

```sql
CREATE TABLE rate_limit_tracker (
  key TEXT PRIMARY KEY,               -- Derived from mailbox or IP
  count INT NOT NULL,
  window_start TIMESTAMPTZ NOT NULL
);
```

**What This Contains:**
- ✅ **Request counts** (prevents spam)
- ❌ **NO user identity** (rate limit by mailbox hash or IP)

---

## 🔥 3. Google/Firebase (FCM)

### What's Stored/Shared with GOOGLE:

**CRITICAL:** Google Firebase is ONLY used for push notification delivery (wake signals).

### What Google/Firebase Sees:

#### FCM Device Token
```
fcm_token: "eA1b2C3d4E5f6..." (unique per device install)
```
- ✅ Google generates this token for push notifications
- ✅ Token is device-specific (not tied to user identity)
- ✅ Token changes on app reinstall
- ❌ Google CANNOT link token to your phone number, email, or identity

#### FCM Push Payloads (Wake Signals)
```json
{
  "message": {
    "token": "eA1b2C3d4E5f6...",
    "data": {
      "epoch": "1704067200",
      "nonce": "random-uuid-here"
    },
    "android": { "priority": "high" }
  }
}
```

**What This Payload Contains:**
- ✅ `epoch`: Unix timestamp (prevents iOS deduplication)
- ✅ `nonce`: Random UUID (prevents iOS deduplication)
- ❌ **NO "type" field** (no "heartbeat" vs "message" distinction)
- ❌ **NO sender info** (no identity, name, or public key)
- ❌ **NO message content** (no encrypted blob, no plaintext)
- ❌ **NO message count** (no "3 new messages")
- ❌ **NO recipient info** (no mailbox hash, no identity)

**CRITICAL PRIVACY DESIGN:**
- 🎭 **Heartbeat FCM payload = Message FCM payload** (byte-for-byte identical)
- 🎭 Google **CANNOT distinguish** heartbeat from real message
- 🎭 Constant traffic pattern (every 10-20 minutes via Poisson distribution)
- 🎭 Real message timing is **statistically hidden** in heartbeat noise

### What Google/Firebase CANNOT See:

❌ **Message Content** - Never sent through FCM (E2E encrypted on Supabase)
❌ **Sender Identity** - Not in FCM payload
❌ **Recipient Identity** - Not in FCM payload
❌ **Message Count** - Not in FCM payload
❌ **Which Pushes Contain Real Messages** - Heartbeats look identical
❌ **Communication Patterns** - Constant heartbeat noise obscures real traffic
❌ **Who You're Messaging** - No identity in FCM
❌ **When You're Actually Communicating** - Heartbeats fire 24/7

### What Google/Firebase CAN See (Metadata):

✅ **Device receives push every 10-20 minutes** (Poisson pattern)
✅ **Push priority is "high"** (wakes device)
✅ **App package name** (com.void.app)
✅ **Device platform** (Android/iOS)
✅ **FCM token** (device identifier, not user identifier)

**Google's Perspective:**
```
Google sees:
- Device ABC123 receives push notifications every ~15 minutes
- App "com.void.app" is using FCM
- Pushes have random epoch + nonce (looks like anti-spam)
- NO IDEA if pushes contain real messages or not
- NO IDEA who is messaging whom
- NO IDEA what messages say
- Pattern looks like: "Normal messaging app with periodic sync"
```

---

## 🔄 Message Flow (End-to-End)

### Sending a Message

**Alice → Bob**

```
1. Alice's Phone (Client):
   - Compose: "Hey Bob!"
   - Encrypt: E2E encrypt with Bob's public key
   - Blob: {sender_public_key, "Hey Bob!", timestamp, nonce}
   - Derive: Calculate Bob's mailbox hash (SHA-256(bob_seed + epoch))

2. Supabase (Server):
   - Store: INSERT INTO message_queue (mailbox_hash, ciphertext, epoch)
   - Queue: Message sits encrypted in Bob's blind mailbox
   - NO DECRYPTION: Server cannot read message (no keys)

3. Supabase → Google FCM:
   - Send: FCM push to Bob's device token
   - Payload: {"epoch": "...", "nonce": "..."}  ← IDENTICAL to heartbeat!
   - NO MESSAGE DATA in FCM payload

4. Google FCM → Bob's Phone:
   - Wake: Device receives FCM push (silent)
   - Cannot Tell: Heartbeat or message? (looks identical)

5. Bob's Phone (Client):
   - Fetch: GET /fetch-mailbox (always 4KB response)
   - Receive: Encrypted blob from Supabase
   - Decrypt: E2E decrypt with Bob's private key
   - Store: Save "Hey Bob!" in local encrypted database
   - Notify: Show "VOID - Activity Detected" notification
   - Delete: Delete message from Supabase (no server history)

6. Bob Opens App:
   - Auth: Biometric authentication
   - Display: Shows "Alice: Hey Bob!" (decrypted from local storage)
```

### Receiving a Heartbeat (No Messages)

```
1. Supabase pg_cron:
   - Check: Which users are due for heartbeat? (Poisson schedule)
   - Bob is due (next_heartbeat_at <= now())

2. Supabase → Google FCM:
   - Send: IDENTICAL FCM push to Bob's device
   - Payload: {"epoch": "...", "nonce": "..."}  ← Same as real message!

3. Google FCM → Bob's Phone:
   - Wake: Device receives FCM push
   - Cannot Tell: Heartbeat or message? (identical)

4. Bob's Phone (Client):
   - Fetch: GET /fetch-mailbox (always 4KB response)
   - Receive: 4KB of pure random noise (magic byte = 0x00)
   - No Messages: Mailbox is empty
   - No Notification: Silent (user unaware)

5. Supabase:
   - Update: next_heartbeat_at = now() + random(600-1200) seconds
   - Schedule: Next heartbeat in 10-20 minutes (Poisson)
```

---

## 🔒 Privacy Analysis

### What Each Party Knows:

#### Your Phone (Client)
**Knows:**
- ✅ Your identity seed (master secret)
- ✅ Your private key (never shared)
- ✅ All message content (decrypted plaintext)
- ✅ All conversation history (permanent)
- ✅ All contact public keys (your address book)
- ✅ When real messages arrive vs heartbeats (after fetch)

#### Supabase Server
**Knows:**
- ✅ Encrypted message blobs (cannot decrypt)
- ✅ Blind mailbox hashes (cannot link to identity)
- ✅ FCM device tokens (Google's tokens)
- ✅ Message arrival timestamps (but not sender/recipient identities)
- ❌ **CANNOT decrypt messages** (no keys)
- ❌ **CANNOT identify users** (blind mailboxes rotate daily)
- ❌ **CANNOT read content** (E2E encrypted)

#### Google/Firebase (FCM)
**Knows:**
- ✅ Device receives pushes every 10-20 minutes
- ✅ App package name (com.void.app)
- ✅ FCM token (device identifier)
- ❌ **CANNOT see message content** (not in FCM payload)
- ❌ **CANNOT distinguish heartbeat from message** (identical payloads)
- ❌ **CANNOT identify users** (no identity in FCM)
- ❌ **CANNOT see communication patterns** (heartbeat noise)

#### Network Observer (ISP/NSA)
**Sees:**
- ✅ HTTPS requests to Supabase every 10-20 minutes
- ✅ Constant 4KB response size (always)
- ✅ Device receives FCM pushes periodically
- ❌ **CANNOT decrypt HTTPS** (TLS encryption)
- ❌ **CANNOT tell if 4KB contains real data** (constant size)
- ❌ **CANNOT distinguish heartbeat from message** (identical traffic)
- ❌ **CANNOT see communication patterns** (hidden in heartbeat noise)

---

## 📊 Data Retention

### Mobile Device (Client)
```
Messages:      PERMANENT (until user deletes app or conversation)
Conversations: PERMANENT (until user deletes)
Contacts:      PERMANENT (until user removes contact)
Identity:      PERMANENT (until user panic wipes or reinstalls)
Drafts:        PERMANENT (until user sends or deletes)
```

### Supabase Database (Server)
```
message_queue:        7 DAYS MAX (auto-deleted after fetch or expiry)
push_registrations:   25 HOURS (re-registered daily, auto-cleanup)
ephemeral_tokens:     10 MINUTES (single-use, auto-deleted)
rate_limit_tracker:   Rolling window (cleanup after rate limit period)
```

**CRITICAL:**
- Messages are **deleted from Supabase immediately after fetch** (no server history)
- Even if you don't fetch, messages **expire after 7 days** (TTL cleanup)
- Server **never retains long-term message history** (no backups of messages)

### Google/Firebase (FCM)
```
FCM Push Logs:  Unknown (Google's internal retention)
FCM Tokens:     Until app uninstall or token refresh
```

**NOTE:** Google may log FCM delivery internally, but:
- ❌ Logs contain NO message content (only wake signals)
- ❌ Logs contain NO identifiable data (epoch + nonce only)
- ❌ Cannot distinguish heartbeat from message (identical)

---

## 🎯 Summary Table

| Data Type | Mobile Device | Supabase | Google/Firebase |
|-----------|---------------|----------|-----------------|
| **Message Content (Plaintext)** | ✅ Encrypted on device | ❌ Never stored | ❌ Never sent |
| **Message Content (Encrypted)** | ✅ Local backup | ✅ Temporary queue (7 days) | ❌ Never sent |
| **Sender Identity** | ✅ Known (contact list) | ❌ Sealed sender | ❌ Never sent |
| **Recipient Identity** | ✅ Known ("me") | ❌ Blind mailbox | ❌ Never sent |
| **Message Count** | ✅ Known locally | ❌ Server doesn't count | ❌ Never sent |
| **Conversation History** | ✅ Full history | ❌ No history (deleted after fetch) | ❌ Never sent |
| **Your Private Key** | ✅ Stored securely | ❌ NEVER sent | ❌ NEVER sent |
| **Your Public Key** | ✅ Stored locally | ❌ Not stored (blind protocol) | ❌ Never sent |
| **FCM Device Token** | ✅ Stored locally | ✅ Temporary (25h) | ✅ Google-generated |
| **Mailbox Hash** | ✅ Derived locally | ✅ Temporary (25h) | ❌ Never sent |
| **Heartbeat Schedule** | ❌ Not stored | ✅ Randomized per device | ❌ Never sent |

---

## 🛡️ Security Properties

### End-to-End Encryption
- 🔒 Messages encrypted on sender's device
- 🔒 Messages decrypted on recipient's device
- 🔒 Server never has decryption keys
- 🔒 Google/FCM never sees message content

### Metadata Minimization
- 🔒 Server doesn't know sender/recipient identities (blind mailboxes)
- 🔒 Google doesn't know if push contains message (heartbeat mixing)
- 🔒 Network observers cannot detect patterns (constant 4KB traffic)
- 🔒 No long-term server-side history (messages deleted after fetch)

### Forward Secrecy
- ⚠️ **Currently NOT implemented** (same keys for all messages)
- 📋 **Future enhancement:** Rotate message encryption keys using Double Ratchet

### Deniability
- 🔒 Sender uses sealed sender (recipient knows sender, but server doesn't)
- 🔒 Heartbeat mixing provides plausible deniability (push could be heartbeat)
- 🔒 No server-side logs linking users to messages

---

## 🚀 Comparison with Other Apps

### Signal
```
Message Content:    E2E encrypted ✅
Sender Metadata:    Sealed sender (recent messages only) ⚠️
Server Storage:     Temporary queue (similar to VOID) ✅
Traffic Patterns:   No obfuscation ❌
Heartbeat Mixing:   No ❌
Google FCM:         Used for delivery, contains metadata ⚠️
Phone Number:       Required for signup ❌
```

### VOID
```
Message Content:    E2E encrypted ✅
Sender Metadata:    Sealed sender (all messages) ✅
Server Storage:     Temporary queue (7 days max) ✅
Traffic Patterns:   Poisson heartbeat obfuscation ✅
Heartbeat Mixing:   Yes (indistinguishable from messages) ✅
Google FCM:         Used ONLY for wake signals (zero metadata) ✅
Phone Number:       NOT required (3-word identity) ✅
```

**Result:** VOID is **more paranoid than Signal** while maintaining the same UX.

---

## 📝 Key Takeaways

### ✅ What Makes VOID Private:

1. **E2E Encryption** - Server never sees plaintext
2. **Blind Mailboxes** - Server doesn't know recipient identity
3. **Sealed Sender** - Server doesn't know sender identity
4. **Poisson Heartbeats** - Google/ISP cannot detect real message timing
5. **Constant 4KB Responses** - Network cannot tell if real data or noise
6. **Generic Notifications** - Android OS sees no metadata
7. **No Server History** - Messages deleted after fetch (7 day max)
8. **No Phone Number** - 3-word identity (no SIM card linkage)

### ⚠️ What Could Be Improved (Future):

1. **Forward Secrecy** - Implement Double Ratchet (key rotation)
2. **Sealed Sender Authentication** - Add cryptographic proof (prevent impersonation)
3. **Self-Destructing Messages** - Client-side timer for message deletion
4. **Metadata-Free Contacts** - Private contact discovery (no server knows your contacts)

---

**End of Data Storage Architecture**
