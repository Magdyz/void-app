# VOID Supabase Backend Setup Instructions

Complete guide to setting up the Supabase backend for VOID's privacy-first messaging architecture.

---

## Prerequisites

- Supabase account (free tier works for development)
- Supabase CLI installed (`npm install -g supabase`)
- Firebase project with Cloud Messaging enabled (for push notifications)
- Basic understanding of PostgreSQL and SQL

---

## Quick Start

```bash
# 1. Initialize Supabase in your project
supabase init

# 2. Link to your Supabase project
supabase link --project-ref your-project-ref

# 3. Run all migrations in order
supabase db push

# 4. Deploy the Edge Function
supabase functions deploy send-push-notification

# 5. Set the FCM server key
supabase secrets set FCM_SERVER_KEY=your-firebase-server-key
```

---

## Detailed Phase-by-Phase Setup

### Phase 1: Create Message Queue Table

**Goal**: Set up the core table where encrypted messages are temporarily stored.

#### Steps:

1. Open Supabase Dashboard → SQL Editor
2. Copy contents from `migrations/01_message_queue.sql`
3. Execute the SQL
4. Verify table creation:
   ```sql
   SELECT * FROM information_schema.tables
   WHERE table_name = 'message_queue';
   ```

#### Test Phase 1:

```sql
-- Insert test message
INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
VALUES (
    'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
    'dGVzdCBjaXBoZXJ0ZXh0',
    1704067200
);

-- Query by mailbox_hash
SELECT * FROM public.message_queue
WHERE mailbox_hash = 'abc123def456abc123def456abc123def456abc123def456abc123def456abcd';

-- Cleanup
DELETE FROM public.message_queue
WHERE mailbox_hash = 'abc123def456abc123def456abc123def456abc123def456abc123def456abcd';
```

**Expected**: Row inserted, queried, and deleted successfully.

---

### Phase 2: Create Push Registrations Table

**Goal**: Link mailbox hashes to FCM tokens for push notifications.

#### Steps:

1. Open Supabase Dashboard → SQL Editor
2. Copy contents from `migrations/02_push_registrations.sql`
3. Execute the SQL
4. Verify table creation:
   ```sql
   SELECT * FROM information_schema.tables
   WHERE table_name = 'push_registrations';
   ```

#### Test Phase 2:

```sql
-- Upsert a registration
INSERT INTO public.push_registrations (mailbox_hash, fcm_token)
VALUES (
    'test123test123test123test123test123test123test123test123test123te',
    'fcm_token_abc_xyz_123'
)
ON CONFLICT (mailbox_hash)
DO UPDATE SET
    fcm_token = EXCLUDED.fcm_token,
    expires_at = now() + INTERVAL '25 hours',
    created_at = now();

-- Verify upsert worked
SELECT * FROM public.push_registrations
WHERE mailbox_hash = 'test123test123test123test123test123test123test123test123test123te';

-- Cleanup
DELETE FROM public.push_registrations
WHERE mailbox_hash = 'test123test123test123test123test123test123test123test123test123te';
```

**Expected**: Registration inserted, then updated on conflict.

---

### Phase 3: Set Up TTL Cleanup

**Goal**: Automatically delete expired messages and push registrations.

#### Option A: Using pg_cron (Supabase Paid Plan)

1. Open Supabase Dashboard → SQL Editor
2. Copy contents from `migrations/03_ttl_cleanup.sql`
3. Execute the SQL (skip if on free tier)
4. Verify cron job:
   ```sql
   SELECT * FROM cron.job WHERE jobname = 'cleanup-expired-records';
   ```

#### Option B: Using Edge Function + External Cron (Free Tier)

1. Create Edge Function for cleanup:
   ```bash
   supabase functions new cleanup-expired
   ```

2. Copy the TypeScript code from `03_ttl_cleanup.sql` (see ALTERNATIVE section)

3. Deploy the function:
   ```bash
   supabase functions deploy cleanup-expired
   ```

4. Set up external cron (e.g., GitHub Actions):
   - Create `.github/workflows/cleanup-cron.yml`
   - Schedule hourly trigger
   - Call the Edge Function endpoint

#### Test Phase 3:

```sql
-- Insert expired message
INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch, expires_at)
VALUES (
    'expired1expired1expired1expired1expired1expired1expired1expired1ex',
    'expired_message',
    1704067200,
    now() - INTERVAL '1 hour'
);

-- Insert expired push registration
INSERT INTO public.push_registrations (mailbox_hash, fcm_token, expires_at)
VALUES (
    'expired2expired2expired2expired2expired2expired2expired2expired2ex',
    'expired_token',
    now() - INTERVAL '1 hour'
);

-- Run cleanup manually
SELECT public.cleanup_expired_records();

-- Verify cleanup worked (should return 0)
SELECT count(*) FROM public.message_queue
WHERE mailbox_hash = 'expired1expired1expired1expired1expired1expired1expired1expired1ex';

SELECT count(*) FROM public.push_registrations
WHERE mailbox_hash = 'expired2expired2expired2expired2expired2expired2expired2expired2ex';
```

**Expected**: Expired rows deleted successfully.

---

### Phase 4: Deploy Push Notification Edge Function

**Goal**: Send silent FCM push when new message arrives.

#### Steps:

1. **Get Firebase Server Key**:
   - Go to Firebase Console → Project Settings → Cloud Messaging
   - Copy the "Server key" (legacy)

2. **Deploy the Edge Function**:
   ```bash
   cd supabase/functions/send-push-notification
   supabase functions deploy send-push-notification
   ```

3. **Set the FCM secret**:
   ```bash
   supabase secrets set FCM_SERVER_KEY=your-firebase-server-key-here
   ```

4. **Set up Database Webhook**:
   - Go to Supabase Dashboard → Database → Webhooks
   - Click "Create a new hook"
   - Configure:
     - **Name**: Push notification on message insert
     - **Table**: message_queue
     - **Events**: INSERT
     - **Type**: HTTP Request
     - **Method**: POST
     - **URL**: `https://your-project-ref.supabase.co/functions/v1/send-push-notification`
     - **Headers**:
       - `Authorization: Bearer [YOUR_ANON_KEY]`
       - `Content-Type: application/json`
   - Save

5. **Verify Edge Function**:
   ```bash
   supabase functions list
   ```

#### Test Phase 4:

```sql
-- 1. Register a test FCM token (use real token from your device)
INSERT INTO public.push_registrations (mailbox_hash, fcm_token)
VALUES (
    'testboxtestboxtestboxtestboxtestboxtestboxtestboxtestboxtestbox',
    'YOUR_ACTUAL_FCM_TOKEN_FROM_DEVICE'
)
ON CONFLICT (mailbox_hash)
DO UPDATE SET fcm_token = EXCLUDED.fcm_token;

-- 2. Insert a message to trigger push
INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
VALUES (
    'testboxtestboxtestboxtestboxtestboxtestboxtestboxtestboxtestbox',
    'dGVzdCBjaXBoZXJ0ZXh0',
    floor(extract(epoch from now()))::bigint
);

-- 3. Check Edge Function logs in Supabase Dashboard
--    Should see: "Push sent successfully to testbox..."

-- 4. Check your device for silent push notification
--    Should receive push with data.epoch and data.nonce

-- Cleanup
DELETE FROM public.message_queue WHERE mailbox_hash LIKE 'testbox%';
DELETE FROM public.push_registrations WHERE mailbox_hash LIKE 'testbox%';
```

**Expected**: Device receives silent push notification with epoch and nonce.

---

### Phase 5: Configure Row-Level Security (RLS)

**Goal**: Ensure clients can only access their own data.

#### Steps:

1. Open Supabase Dashboard → SQL Editor
2. Copy contents from `migrations/05_rls_policies.sql`
3. Execute the SQL
4. Verify RLS is enabled:
   ```sql
   SELECT tablename, rowsecurity FROM pg_tables
   WHERE schemaname = 'public'
   AND tablename IN ('message_queue', 'push_registrations');
   ```

#### Test Phase 5:

Use Supabase JavaScript client to test:

```javascript
import { createClient } from '@supabase/supabase-js'

const supabase = createClient('YOUR_SUPABASE_URL', 'YOUR_ANON_KEY')

// Insert test data
await supabase.from('message_queue').insert([
  {
    mailbox_hash: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    ciphertext: 'msg_for_aaa',
    epoch: 1704067200
  },
  {
    mailbox_hash: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
    ciphertext: 'msg_for_bbb',
    epoch: 1704067200
  }
])

// Test: Select mailbox 'aaa' (should work)
const { data } = await supabase
  .from('message_queue')
  .select('*')
  .eq('mailbox_hash', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
console.log(data) // Should return 1 row

// Test: Delete message
await supabase
  .from('message_queue')
  .delete()
  .eq('mailbox_hash', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')

// Cleanup
await supabase.from('message_queue').delete().like('mailbox_hash', 'aaaa%')
await supabase.from('message_queue').delete().like('mailbox_hash', 'bbbb%')
```

**Expected**: Queries only return data matching the mailbox_hash filter.

---

### Phase 6: Add Validation Constraints

**Goal**: Prevent invalid data and abuse at the database level.

#### Steps:

1. Open Supabase Dashboard → SQL Editor
2. Copy contents from `migrations/06_validation_constraints.sql`
3. Execute the SQL
4. Verify constraints:
   ```sql
   SELECT conname, contype FROM pg_constraint
   WHERE conrelid = 'public.message_queue'::regclass;
   ```

#### Test Phase 6:

```sql
-- Test 1: Valid insert (should work)
INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch, expires_at)
VALUES (
    'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
    'dGVzdCBjaXBoZXJ0ZXh0',
    floor(extract(epoch from now()))::bigint,
    now() + INTERVAL '3 days'
);

-- Test 2: Invalid mailbox_hash (should FAIL)
INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
VALUES ('short', 'dGVzdA==', floor(extract(epoch from now()))::bigint);
-- Expected: "violates check constraint"

-- Test 3: Epoch too old (should FAIL)
INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
VALUES (
    'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
    'dGVzdA==',
    1577836800  -- 2020
);
-- Expected: "Epoch ... is too old"

-- Cleanup
DELETE FROM public.message_queue WHERE mailbox_hash LIKE 'abc123%';
```

**Expected**: Valid inserts succeed, invalid inserts fail with descriptive errors.

---

## Environment Variables

Set these in Supabase Dashboard → Settings → Edge Functions:

| Variable | Description | Required For |
|----------|-------------|--------------|
| `SUPABASE_URL` | Your Supabase project URL | Auto-set |
| `SUPABASE_SERVICE_ROLE_KEY` | Service role key | Auto-set |
| `FCM_SERVER_KEY` | Firebase Cloud Messaging server key | Phase 4 |
| `CLEANUP_SECRET` | Secret token for cleanup endpoint (optional) | Phase 3 (Edge Function) |

---

## Client SDK Configuration

Update your Android app to use Supabase:

### 1. Add Supabase Dependency

```kotlin
// In app/build.gradle.kts
dependencies {
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.0.0")
    implementation("io.github.jan-tennert.supabase:realtime-kt:2.0.0")
}
```

### 2. Initialize Supabase Client

```kotlin
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

val supabase = createSupabaseClient(
    supabaseUrl = "https://your-project-ref.supabase.co",
    supabaseKey = "your-anon-key"
) {
    install(Postgrest)
}
```

### 3. Send Message

```kotlin
data class MessageQueueInsert(
    val mailbox_hash: String,
    val ciphertext: String,
    val epoch: Long,
    val expires_at: String
)

suspend fun sendMessage(
    recipientMailboxHash: String,
    encryptedMessage: String,
    epoch: Long
) {
    supabase.from("message_queue").insert(
        MessageQueueInsert(
            mailbox_hash = recipientMailboxHash,
            ciphertext = encryptedMessage,
            epoch = epoch,
            expires_at = (System.currentTimeMillis() / 1000 + 7 * 24 * 3600).toString()
        )
    )
}
```

### 4. Fetch Messages

```kotlin
data class MessageQueueRow(
    val id: String,
    val mailbox_hash: String,
    val ciphertext: String,
    val epoch: Long,
    val created_at: String
)

suspend fun fetchMessages(myMailboxHash: String): List<MessageQueueRow> {
    return supabase.from("message_queue")
        .select()
        .eq("mailbox_hash", myMailboxHash)
        .decodeList<MessageQueueRow>()
}
```

### 5. Delete Fetched Messages

```kotlin
suspend fun deleteMessages(messageIds: List<String>) {
    supabase.from("message_queue")
        .delete {
            filter {
                isIn("id", messageIds)
            }
        }
}
```

### 6. Register FCM Token

```kotlin
suspend fun registerPushToken(mailboxHash: String, fcmToken: String) {
    supabase.from("push_registrations")
        .upsert(
            mapOf(
                "mailbox_hash" to mailboxHash,
                "fcm_token" to fcmToken
            )
        )
}
```

---

## Migration from Old Server

### Changes Required:

1. **Remove old server dependencies**:
   - Remove `:server` module from `settings.gradle.kts` ✅
   - Remove old server directory ✅

2. **Update NetworkClient**:
   - Replace `KtorNetworkClient` endpoints with Supabase SDK calls
   - Remove WebSocket logic (or replace with Supabase Realtime)

3. **Update MessageSyncWorker**:
   - Change to fetch from Supabase instead of old server
   - Use Supabase client instead of Ktor HTTP client

4. **Update FirebaseService**:
   - Keep FCM receiving logic (still used for silent pushes)
   - Remove old server token registration endpoint

5. **Remove Redis dependencies**:
   - No longer needed (Supabase uses PostgreSQL)

---

## Monitoring & Maintenance

### View Edge Function Logs

```bash
supabase functions logs send-push-notification --tail
```

Or in Supabase Dashboard → Edge Functions → send-push-notification → Logs

### Monitor Cron Jobs (pg_cron)

```sql
-- View scheduled jobs
SELECT * FROM cron.job;

-- View job execution history
SELECT * FROM cron.job_run_details
ORDER BY start_time DESC
LIMIT 20;
```

### Database Metrics

```sql
-- Check message queue size
SELECT count(*) FROM public.message_queue;

-- Check push registrations count
SELECT count(*) FROM public.push_registrations;

-- Check expired messages (should be 0 if cleanup works)
SELECT count(*) FROM public.message_queue WHERE expires_at < now();
SELECT count(*) FROM public.push_registrations WHERE expires_at < now();
```

---

## Troubleshooting

### Push Notifications Not Arriving

1. Check Edge Function logs for errors
2. Verify FCM_SERVER_KEY is set correctly
3. Test FCM token directly with Firebase Console
4. Ensure webhook is configured and active
5. Check that push_registration exists for the mailbox

### Messages Not Being Deleted

1. Check if cleanup job is running (pg_cron)
2. Verify cleanup function exists and is accessible
3. Check Edge Function logs (if using Edge Function cleanup)
4. Manually run `SELECT public.cleanup_expired_records();`

### RLS Blocking Queries

1. Ensure client is using `.eq('mailbox_hash', ...)` filter
2. Check RLS policies are enabled
3. Verify using correct anon key
4. Test with service role key to bypass RLS (debugging only)

---

## Security Checklist

- [ ] RLS enabled on both tables
- [ ] FCM_SERVER_KEY stored as secret (not in code)
- [ ] Anon key rate-limited (Supabase Dashboard → API Settings)
- [ ] Service role key NEVER exposed to client
- [ ] Database backups enabled
- [ ] SSL/TLS enforced for all connections
- [ ] Validation constraints active on all columns
- [ ] Webhook uses authenticated endpoints
- [ ] Edge Functions using latest runtime

---

## Cost Optimization

**Free Tier Limits**:
- 500 MB database storage
- 2 GB bandwidth per month
- 500,000 Edge Function invocations per month
- 2 GB Edge Function bandwidth

**Tips to Stay Under Limits**:
1. Enable aggressive TTL (shorter than 7 days)
2. Use client-side polling instead of Realtime for free tier
3. Compress ciphertext before storing
4. Monitor usage in Supabase Dashboard

---

## Next Steps

1. Complete all 6 phases in order
2. Test each phase thoroughly before proceeding
3. Update Android app to use Supabase SDK
4. Remove old server code and dependencies
5. Test end-to-end message flow
6. Deploy to production
7. Monitor logs and metrics

---

## Support

- Supabase Docs: https://supabase.com/docs
- VOID Architecture: See `VOID_Server_Implementation_Guide.md`
- Issues: Report in project repository

---

**Migration completed! Your VOID backend now runs on Supabase with enhanced privacy and scalability.**
