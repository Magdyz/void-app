# Poisson Ghost Protocol - Implementation Guide

## Overview

The Poisson Ghost Protocol is a privacy enhancement for VOID that provides state-of-the-art traffic analysis resistance. It makes message timing and presence indistinguishable to network observers (Google, ISP, etc.).

## What Was Implemented

### Privacy Enhancements

| Aspect | Before | After (Poisson Ghost) |
|--------|--------|----------------------|
| **Push Timing** | Every 15 min (fixed) | Random 10-20 min per user |
| **Server Load** | Spikes when messages arrive | Constant background hum |
| **Response Size** | Variable (1KB-5KB) | Fixed 4KB always |
| **Empty Mailbox** | 0 bytes returned | 4KB noise returned |
| **Real Message** | N bytes | 4KB (padded) |
| **Delivery** | Instant on arrival | Next heartbeat (avg 5-10 min) |
| **Network Pattern** | Google sees message timing | Google sees random ticks |
| **ISP Visibility** | Variable response sizes | Constant 4KB packets |

### Components Implemented

#### Server-Side (Supabase)

1. **Database Migration** (`migrations/12_poisson_ghost_heartbeat.sql`)
   - Adds heartbeat tracking to `push_registrations` table
   - Random intervals (600-1200 seconds) per user
   - ±10% jitter for additional randomness

2. **Edge Function: heartbeat-sender** (`functions/heartbeat-sender/index.ts`)
   - Runs every minute via pg_cron
   - Queries users due for heartbeat
   - Sends batch FCM pushes
   - Updates next heartbeat with random interval

3. **Edge Function: fetch-mailbox** (`functions/fetch-mailbox/index.ts`)
   - Always returns exactly 4KB
   - Magic byte header (0x01 = real, 0x00 = noise)
   - Real messages: JSON + random padding
   - Empty mailbox: pure random noise

4. **Cron Scheduler** (`migrations/13_poisson_ghost_scheduler.sql`)
   - pg_cron job runs every minute
   - Calls heartbeat-sender Edge Function
   - Self-healing configuration

#### Client-Side (Android)

1. **VoidFirebaseService** (updated)
   - Detects heartbeat vs message notifications
   - Logs protocol type for debugging
   - Always triggers mailbox fetch

2. **FetchMailboxClient** (new)
   - Calls fetch-mailbox Edge Function
   - Parses 4KB binary responses
   - Checks magic byte
   - Extracts messages or discards noise

3. **MessageRepository** (updated)
   - Uses FetchMailboxClient if available
   - Falls back to legacy MessageFetcher
   - Backward compatible

4. **Dependency Injection** (updated)
   - FetchMailboxClient registered in NetworkModule
   - Auto-injected into MessageRepository

## Deployment Steps

### 1. Apply Database Migrations

```bash
cd supabase
supabase migration up
```

This applies:
- `12_poisson_ghost_heartbeat.sql` - Heartbeat tracking
- `13_poisson_ghost_scheduler.sql` - Cron scheduler

### 2. Deploy Edge Functions

```bash
# Deploy heartbeat sender
supabase functions deploy heartbeat-sender

# Deploy fetch-mailbox
supabase functions deploy fetch-mailbox
```

### 3. Configure Supabase Settings

Run these SQL commands in Supabase SQL Editor:

```sql
-- Set your Supabase function URL
ALTER DATABASE postgres SET app.supabase_function_url =
  'https://<your-project-ref>.supabase.co/functions/v1/heartbeat-sender';

-- Set your anon key
ALTER DATABASE postgres SET app.supabase_anon_key = '<your-anon-key>';
```

**Get your values:**
- Project URL: Supabase Dashboard → Settings → API → Project URL
- Anon Key: Supabase Dashboard → Settings → API → Project API keys → `anon` `public`

### 4. Verify Cron Job

```sql
-- Check cron job exists
SELECT * FROM cron.job WHERE jobname = 'poisson-ghost-heartbeat';

-- View recent runs
SELECT * FROM cron.job_run_details
WHERE jobid = (SELECT jobid FROM cron.job WHERE jobname = 'poisson-ghost-heartbeat')
ORDER BY start_time DESC LIMIT 10;

-- Test manually
SELECT trigger_heartbeat_sender();
```

### 5. Build and Deploy Android App

```bash
# Clean build to ensure all changes are compiled
./gradlew clean

# Build debug APK
./gradlew :app:assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How It Works

### Heartbeat Flow

```
┌──────────────┐
│  pg_cron     │ Runs every 1 minute
│  (server)    │
└──────┬───────┘
       │
       ▼
┌──────────────────────────────┐
│  heartbeat-sender Function   │ Queries users due for heartbeat
│  - get_due_heartbeats()     │ (up to 100 per run)
│  - Send FCM pushes          │
│  - update_next_heartbeat()  │ Random 10-20 min + jitter
└──────┬───────────────────────┘
       │
       ▼
┌──────────────┐
│   Google FCM │ Silent push with type="heartbeat"
└──────┬───────┘
       │
       ▼
┌────────────────────────┐
│  VoidFirebaseService   │ Receives heartbeat tick
│  (Android)             │
└──────┬─────────────────┘
       │
       ▼
┌────────────────────────┐
│  MessageSyncWorker     │ Fetches mailbox
└──────┬─────────────────┘
       │
       ▼
┌────────────────────────┐
│  FetchMailboxClient    │ Calls fetch-mailbox Edge Function
│  - POST with token     │
│  - Receives 4KB        │
│  - Checks magic byte   │
└──────┬─────────────────┘
       │
       ▼
┌────────────────────────┐
│  fetch-mailbox Function│ Always returns 4KB
│  - Validates token     │ - Magic byte 0x01 = real messages
│  - Fetches messages    │ - Magic byte 0x00 = noise
│  - Pads to 4KB         │
└────────────────────────┘
```

### Message Delivery Flow

```
1. User A sends message to User B
   ↓
2. Server stores encrypted blob in User B's mailbox
   ↓
3. Server does NOT send instant push (unlike old protocol)
   ↓
4. User B's next scheduled heartbeat arrives (random 10-20 min)
   ↓
5. Client fetches mailbox (always 4KB)
   ↓
6. Client checks magic byte:
   - 0x01: Parse messages, decrypt, show notification
   - 0x00: Discard noise, no action
```

## Testing

### 1. Verify Heartbeat Scheduler

```sql
-- Check if heartbeats are being sent
SELECT * FROM cron.job_run_details
WHERE jobid = (SELECT jobid FROM cron.job WHERE jobname = 'poisson-ghost-heartbeat')
ORDER BY start_time DESC LIMIT 5;

-- Check next scheduled heartbeats
SELECT
  mailbox_hash,
  next_heartbeat_at,
  heartbeat_interval_seconds,
  next_heartbeat_at - now() as time_until_next
FROM push_registrations
ORDER BY next_heartbeat_at ASC
LIMIT 10;
```

### 2. Monitor Android Logs

```bash
# Filter for Poisson Ghost logs
adb logcat | grep -E "POISSON_GHOST|🫀|Heartbeat"

# Expected output:
# VoidFirebaseService: 🫀 Heartbeat tick received (Poisson Ghost Protocol)
# MessageRepository: 📥 [POISSON_GHOST] Using 4KB padded fetch protocol
# FetchMailboxClient: ✓ Real messages detected (magic byte: 0x01)
# FetchMailboxClient: ○ Noise response (magic byte: 0x00) - no messages
```

### 3. Test Message Delivery

1. Send a message from Device A to Device B
2. **Do NOT expect instant delivery** (old protocol)
3. Wait for Device B's next heartbeat (avg 5-10 min, max 20 min)
4. Observe logs show 4KB fetch and message arrival

### 4. Verify 4KB Responses

```bash
# Monitor network traffic (requires root or proxy)
# All fetch-mailbox responses should be exactly 4096 bytes

adb logcat | grep "FetchMailboxClient"

# Expected output:
# FetchMailboxClient: 📥 Fetching mailbox via Poisson Ghost protocol
# FetchMailboxClient: 📦 Response: 245 bytes data + 3851 bytes padding = 4096 bytes total
# OR
# FetchMailboxClient: 🎭 Noise response: 4096 bytes random data
```

## Privacy Verification

### What Network Observers See

**Google FCM:**
- Random push notifications at varying intervals (10-20 min)
- All pushes look identical (epoch + nonce + type)
- Cannot determine if heartbeat or real message
- Cannot correlate sender/receiver timing

**ISP / Network Tap:**
- Constant 4KB HTTPS responses to Supabase
- No size variation reveals message presence
- Response timing appears random (Poisson distribution)
- Cannot distinguish empty mailbox from messages

### Attack Resistance

| Attack | Before | After (Poisson Ghost) |
|--------|--------|----------------------|
| **Timing Correlation** | ISP sees message arrival = instant push | ISP sees random intervals, no correlation |
| **Size Analysis** | 0 bytes (empty) vs N bytes (message) | Always 4KB |
| **Frequency Analysis** | Spike in traffic when messaging | Constant background noise |
| **Intersection Attack** | Can correlate A sends → B receives | Randomized delays break correlation |

## Configuration Options

### Adjust Heartbeat Intervals

Edit `supabase/migrations/12_poisson_ghost_heartbeat.sql`:

```sql
-- Current: 600-1200 seconds (10-20 min)
-- Change to different range:

UPDATE public.push_registrations
SET heartbeat_interval_seconds = 300 + floor(random() * 300)::int  -- 5-10 min
-- OR
SET heartbeat_interval_seconds = 900 + floor(random() * 900)::int  -- 15-30 min
```

### Adjust Response Size

Edit `supabase/functions/fetch-mailbox/index.ts`:

```typescript
const RESPONSE_SIZE = 2048  // Change to 4096 for 4KB, etc.
```

**Note:** Larger responses = more padding overhead, but even more indistinguishable.

### Priority Override (Optional)

For urgent messages, you can still send instant pushes alongside heartbeats:

Edit `supabase/functions/send-push-notification/index.ts` to send both:
1. Instant push (for urgent delivery)
2. Regular heartbeat continues (for cover traffic)

Google sees both as identical pushes, maintaining privacy.

## Backward Compatibility

The implementation is fully backward compatible:

- **Old clients:** Continue to use MessageFetcher (direct Postgrest)
- **New clients:** Automatically use FetchMailboxClient if available
- **Server:** Supports both protocols simultaneously
- **Fallback:** If Edge Function unavailable, uses legacy path

To **disable** Poisson Ghost and revert to instant delivery:

```sql
-- Unschedule heartbeat sender
SELECT cron.unschedule('poisson-ghost-heartbeat');

-- Clear heartbeat fields (optional)
UPDATE push_registrations SET next_heartbeat_at = NULL;
```

## Performance Impact

### Server
- **CPU:** Minimal (1 cron job per minute, processes 100 users max)
- **Bandwidth:** Constant (4KB per fetch, ~1-2KB per heartbeat push)
- **Database:** Lightweight queries (indexed on next_heartbeat_at)

### Client
- **Battery:** Similar to before (10-20 min intervals same as old 15 min)
- **Network:** Constant 4KB fetches (vs variable before)
- **Latency:** Message delivery delayed by 5-10 min avg (acceptable for privacy)

## Troubleshooting

### Heartbeats Not Sending

```sql
-- Check cron job status
SELECT * FROM cron.job WHERE jobname = 'poisson-ghost-heartbeat';

-- Check configuration
SELECT current_setting('app.supabase_function_url', true);
SELECT current_setting('app.supabase_anon_key', true);

-- Manually trigger
SELECT trigger_heartbeat_sender();
```

### Edge Function Errors

```bash
# View Edge Function logs
supabase functions logs heartbeat-sender
supabase functions logs fetch-mailbox

# Common issues:
# - Missing FIREBASE_SERVICE_ACCOUNT env var
# - Invalid token authentication
# - Network timeout
```

### Client Not Using Poisson Ghost

Check Android logs:

```bash
adb logcat | grep "MessageRepository"

# Should see:
# [POISSON_GHOST] Using 4KB padded fetch protocol

# If you see:
# [LEGACY] Using direct Postgrest fetch
# Then FetchMailboxClient is not injected (check NetworkModule)
```

## Security Considerations

### Edge Function Security

Both Edge Functions use:
- Ephemeral token authentication (same as direct Postgrest)
- Service role bypass for RLS (server-side validation)
- HTTPS only
- No message content in logs

### Random Number Quality

- Uses `crypto.getRandomValues()` (cryptographically secure)
- Poisson distribution approximated via uniform random intervals
- Jitter (±10%) prevents periodic patterns

### Information Leakage

What the server knows:
- Mailbox hashes (blind, rotated daily)
- FCM tokens (ephemeral, rotated daily)
- Message arrival times (but not sender/content)
- Heartbeat intervals (but not message correlation)

What the server does NOT know:
- User identities (blind mailboxes)
- Message content (E2E encrypted)
- Sender/receiver relationship (sealed sender)
- Whether heartbeat was for message or empty (always sends)

## Future Enhancements

### Constant-Rate Protocol

Upgrade to true constant-rate:
- Send heartbeat at fixed intervals (e.g., every 10 min sharp)
- All users synchronized to global clock
- Perfect traffic uniformity
- Trade-off: Higher battery/bandwidth cost

### Onion Routing Integration

Combine with Tor/I2P:
- Hide IP address from Supabase
- Prevent server from seeing user location
- Complete metadata protection

### Decoy Messages

Server-side decoy injection:
- Server randomly injects fake messages
- Client discards based on MAC verification
- ISP cannot tell real from decoy

## Credits

Inspired by:
- Signal's sealed sender (metadata hiding)
- Tor's traffic analysis resistance (timing obfuscation)
- Nym's Poisson mixnet (constant-rate protocols)

## License

Same as VOID project (check root LICENSE file).

---

**Implementation Date:** January 2026
**Protocol Version:** 1.0
**Status:** Production Ready ✅
