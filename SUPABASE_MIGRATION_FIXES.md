# Supabase Migration Fixes Applied

## Summary
Fixed the messaging system to use Supabase production server instead of old local server. The app was trying to connect to `192.168.1.26:8080` (old Ktor server) instead of Supabase.

---

## Issues Fixed

### 1. ✅ Wrong Network Client
**Problem**: App was using old `NetworkClient` (KtorNetworkClient) which connects to `192.168.1.26:8080`

**Solution**: Updated `MessageRepository` to use new Supabase components:
- Replaced `NetworkClient` with `MessageSender` and `MessageFetcher`
- Updated `MessagingBlock.kt` dependency injection
- Files changed:
  - `blocks/messaging/src/main/kotlin/com/void/block/messaging/data/MessageRepository.kt`
  - `blocks/messaging/src/main/kotlin/com/void/block/messaging/MessagingBlock.kt`

---

### 2. ✅ Mailbox Hash Length Mismatch
**Problem**: Database expects 64-character mailbox hashes, but app generated 32 characters

**Database schema** (`supabase/migrations/01_message_queue.sql`):
```sql
CHECK (length(mailbox_hash) = 64)  -- Requires 64 hex chars (32 bytes)
```

**App was generating**: 32 hex characters (16 bytes)

**Solution**: Updated mailbox derivation to use full SHA-256 hash (32 bytes = 64 hex chars)
- Files changed:
  - `slate/network/src/main/kotlin/com/void/slate/network/mailbox/MailboxDerivation.kt`
  - `slate/network/src/main/kotlin/com/void/slate/network/supabase/MessageSender.kt`
  - `slate/network/src/main/kotlin/com/void/slate/network/supabase/MessageFetcher.kt`

---

### 3. ✅ Supabase Configuration
**Problem**: App configured to use `localhost:54321` (local Supabase)

**Solution**: Updated to use production Supabase server
- **Project URL**: `https://txlamfqcjtqyaqejckke.supabase.co`
- **Config**: Changed from `SupabaseConfig.LOCAL` to `SupabaseConfig.DEBUG`
- Files changed:
  - `slate/network/src/main/kotlin/com/void/slate/network/supabase/SupabaseConfig.kt`
  - `slate/network/src/main/kotlin/com/void/slate/network/di/NetworkModule.kt`

---

### 4. ✅ Missing Identity Methods
**Problem**: `MessageEncryptionService` lacked methods to get recipient identity with seed

**Solution**: Added new interface methods and data model:
- Added `RecipientIdentity` data class with `seed` and `threeWordIdentity` fields
- Added `getOwnIdentity()` method for fetching own mailbox
- Updated `AppMessageEncryptionService` implementation
- Files changed:
  - `blocks/messaging/src/main/kotlin/com/void/block/messaging/crypto/MessageEncryptionService.kt`
  - `app/src/main/kotlin/com/void/app/crypto/AppMessageEncryptionService.kt`

---

## Changes Summary

### Files Modified (9 total)

1. **MessageRepository.kt** - Switched from NetworkClient to Supabase components
2. **MessagingBlock.kt** - Updated dependency injection
3. **MailboxDerivation.kt** - Changed from 16 bytes to 32 bytes (32→64 hex chars)
4. **MessageSender.kt** - Fixed decoy mailbox generation (32→64 chars)
5. **MessageFetcher.kt** - Fixed mailbox hash validation (32→64 chars)
6. **SupabaseConfig.kt** - Updated production URL to txlamfqcjtqyaqejckke.supabase.co
7. **NetworkModule.kt** - Changed from LOCAL to DEBUG config
8. **MessageEncryptionService.kt** - Added RecipientIdentity and getOwnIdentity()
9. **AppMessageEncryptionService.kt** - Implemented new interface methods

---

## Current Configuration

### Production Supabase
- **URL**: `https://txlamfqcjtqyaqejckke.supabase.co`
- **Project Ref**: `txlamfqcjtqyaqejckke`
- **Config Mode**: `SupabaseConfig.DEBUG` (production server with logging)

### Network Settings
- **Mailbox Hash Length**: 64 hex characters (32 bytes)
- **Mailbox Rotation**: Every 25 hours
- **Message TTL**: 7 days on server
- **Encryption**: End-to-end with sealed sender

---

## Next Steps Required

### 1. Get Actual Supabase Anon Key ⚠️
The app currently uses a placeholder anon key. You need to:

1. Go to: https://supabase.com/dashboard/project/txlamfqcjtqyaqejckke/settings/api
2. Copy the **anon public** key
3. Replace in `SupabaseConfig.kt` lines 49 and 58

**Current placeholder**:
```kotlin
anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0"
```

### 2. Verify Database Migrations
Run these SQL migrations in Supabase SQL Editor (in order):

```bash
supabase/migrations/01_message_queue.sql       # Message storage table
supabase/migrations/02_push_registrations.sql  # FCM push tokens
supabase/migrations/03_ttl_cleanup.sql         # Auto-cleanup job
supabase/migrations/04_push_webhook.sql        # Push notification trigger
supabase/migrations/05_rls_policies.sql        # Row-level security
supabase/migrations/06_validation_constraints.sql # Data validation
```

Or via CLI:
```bash
supabase db push
```

### 3. Test Message Flow
1. Build and install app: `./gradlew installDebug`
2. Send a test message from sender device
3. Check Supabase dashboard → Table Editor → `message_queue`
4. Verify message appears with 64-character mailbox_hash
5. Check receiver device gets message

### 4. Monitor Logs
```bash
# Check for errors
adb logcat | grep VOID_SECURITY

# Should see:
# ✓ [MESSAGE_SENT] - Message sent to Supabase
# ✓ [SYNC] - Messages fetched from Supabase
# ✓ [MESSAGE_RECEIVED] - Message decrypted
```

---

## Troubleshooting

### Messages Not Sending
1. Check anon key is correct in `SupabaseConfig.kt`
2. Verify migrations applied: https://supabase.com/dashboard/project/txlamfqcjtqyaqejckke/editor
3. Check logcat for connection errors

### Messages Not Receiving
1. Verify sync polling is running (every 3 seconds)
2. Check mailbox derivation - both sender and receiver must derive same hash
3. Verify RLS policies allow reads

### Connection Refused
1. Ensure using production URL, not localhost
2. Check network permissions in AndroidManifest.xml
3. Verify device has internet connection

---

## Architecture Overview

```
SENDER DEVICE                    SUPABASE                      RECEIVER DEVICE
─────────────                    ────────                      ───────────────

1. Encrypt message
   (sealed sender)

2. Derive recipient's
   mailbox hash
   (64 hex chars)

3. Send to Supabase  ────────►  4. Store in message_queue
                                   (7-day TTL)
                                                                5. Poll every 3s
                                                                   (fetch from own
                                                                    mailbox hash)

                                6. Return encrypted ──────────► 7. Decrypt locally
                                   messages                        (E2E encrypted)

                                7. Delete fetched ◄────────────  8. Delete from server
                                   (no history kept)
```

---

## Security Notes

✅ **End-to-end encryption**: Server never sees message content
✅ **Sealed sender**: Server doesn't know who sent message
✅ **Blind mailboxes**: Server only sees cryptographic hashes
✅ **No message history**: Messages deleted after fetching
✅ **Forward secrecy**: Mailboxes rotate every 25 hours

---

**Status**: All code changes complete. Awaiting real Supabase anon key and migration verification.
