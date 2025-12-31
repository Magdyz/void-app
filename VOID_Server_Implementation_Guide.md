# VOID Server Implementation Guide (Supabase)

Instructions for server team to implement the backend for VOID's sync architecture.

---

## Phase 1: Message Queue Table

### What to Build
Create the `message_queue` table where encrypted messages are stored until recipients fetch them.

### Table Schema
| Column | Type | Notes |
|--------|------|-------|
| id | uuid | Primary key, auto-generated |
| mailbox_hash | text | 32-char hex string (recipient's blind mailbox) |
| ciphertext | text | Base64-encoded encrypted message blob |
| epoch | bigint | Unix timestamp (seconds), floored to 15s intervals |
| expires_at | timestamptz | When message should be deleted (default: 7 days) |
| created_at | timestamptz | Auto-set on insert |

### Requirements
- Index on `mailbox_hash` (primary lookup)
- Index on `expires_at` (for TTL cleanup)
- Index on `epoch` (for filtering)
- RLS enabled: clients can only SELECT/DELETE rows where they provide the correct `mailbox_hash`

### Test ✓
1. Insert a test row with mailbox_hash = `abc123...`, ciphertext = `test`, epoch = current epoch
2. Query: SELECT where mailbox_hash = `abc123...` → should return the row
3. Query: SELECT where mailbox_hash = `wrong` → should return nothing
4. Delete the test row by id → should succeed

---

## Phase 2: Push Registrations Table

### What to Build
Create `push_registrations` table linking mailbox addresses to FCM tokens.

### Table Schema
| Column | Type | Notes |
|--------|------|-------|
| mailbox_hash | text | Primary key, 32-char hex string |
| fcm_token | text | Firebase Cloud Messaging token |
| expires_at | timestamptz | Registration expiry (25 hours from creation) |
| created_at | timestamptz | Auto-set on insert |

### Requirements
- `mailbox_hash` is primary key (one registration per mailbox)
- Upsert support (client re-registers daily due to mailbox rotation)
- Index on `expires_at` (for TTL cleanup)

### Test ✓
1. Upsert row: mailbox_hash = `test123`, fcm_token = `token_abc`
2. Upsert same mailbox_hash with different token → should update, not duplicate
3. Query by mailbox_hash → should return latest token
4. Delete by mailbox_hash → should succeed

---

## Phase 3: TTL Cleanup

### What to Build
Scheduled job to delete expired records from both tables.

### Logic
Run every hour:
1. Delete from `message_queue` where `expires_at < now()`
2. Delete from `push_registrations` where `expires_at < now()`

### Implementation Options
- **pg_cron** extension (recommended): Schedule SQL directly in Postgres
- **Supabase Edge Function** + external cron: HTTP trigger that runs cleanup

### Test ✓
1. Insert a message_queue row with `expires_at = now() - 1 hour`
2. Insert a push_registrations row with `expires_at = now() - 1 hour`
3. Run cleanup job
4. Query both tables → expired rows should be gone

---

## Phase 4: Send Push Notification Edge Function

### What to Build
Edge function that sends silent FCM push when a new message is inserted.

### Trigger
Database webhook on `message_queue` INSERT

### Function Logic
1. Receive new row data (contains `mailbox_hash`, `epoch`)
2. Look up FCM token: SELECT from `push_registrations` WHERE `mailbox_hash` = row.mailbox_hash
3. If token found, send silent FCM push:
   ```json
   {
     "to": "<fcm_token>",
     "data": {
       "epoch": "<epoch_value>",
       "nonce": "<random_uuid>"
     },
     "content_available": true,
     "priority": "high"
   }
   ```
4. If no token found (user disabled push), do nothing — client will poll

### Important
- **Silent push only**: No `notification` field, only `data`
- **No message content**: Never include ciphertext or sender info in push
- Push just "wakes" the app to fetch from mailbox

### Test ✓
1. Register a test FCM token for mailbox_hash `testbox`
2. Insert a message to `testbox`
3. Check Firebase console or test device → should receive silent push with epoch
4. Insert message to unknown mailbox → no push sent, no error

---

## Phase 5: Row-Level Security (RLS)

### What to Build
Secure access so clients can only access their own data.

### message_queue Policies
| Operation | Policy |
|-----------|--------|
| SELECT | Allow if request provides mailbox_hash that matches row |
| DELETE | Allow if request provides mailbox_hash that matches row |
| INSERT | Allow (authenticated anon key) |

### push_registrations Policies
| Operation | Policy |
|-----------|--------|
| SELECT | Allow if mailbox_hash matches |
| INSERT/UPDATE | Allow (upsert for own mailbox) |
| DELETE | Allow if mailbox_hash matches |

### Key Concept
Clients don't authenticate with user IDs. They prove ownership by knowing the correct mailbox_hash (derived from their private key). Server never knows who owns which mailbox.

### Test ✓
1. Insert message to mailbox `aaa`
2. Try to SELECT with mailbox_hash = `bbb` → should return nothing
3. Try to DELETE with mailbox_hash = `bbb` → should affect 0 rows
4. SELECT/DELETE with mailbox_hash = `aaa` → should work

---

## Phase 6: Message Insert Endpoint

### What to Build
Allow clients to insert messages into the queue.

### Expected Request
Client calls Supabase `message_queue` table with:
```json
{
  "mailbox_hash": "32-char-hex-string",
  "ciphertext": "base64-encoded-blob",
  "epoch": 1234567890,
  "expires_at": "2025-01-07T12:00:00Z"
}
```

### Server Validation
- `mailbox_hash`: Must be 32 hex characters
- `ciphertext`: Must be valid base64, max 64KB
- `epoch`: Must be within ±1 hour of server time
- `expires_at`: Must be within 1-7 days from now

### Test ✓
1. Insert valid message → success, returns id
2. Insert with 64-char mailbox_hash → reject (wrong length)
3. Insert with epoch from yesterday → reject (too old)
4. Insert with expires_at = 30 days → reject (too far)

---

## Quick Reference: Data Flow

```
SENDER                          SERVER                          RECIPIENT
──────                          ──────                          ─────────
                                
1. Derive recipient's           
   mailbox_hash                  
                                
2. Encrypt message              
   (sealed sender)              
                                
3. POST to message_queue  ────► 4. Store in message_queue
                                                         
                                5. Lookup push_registrations
                                   by mailbox_hash
                                                         
                                6. Send silent FCM push ──────► 7. Receive push (epoch only)
                                   (epoch, nonce only)          
                                                                8. Derive own mailbox_hash
                                                         
                          ◄──── 9. SELECT * FROM message_queue
                                   WHERE mailbox_hash = X
                                                         
                                10. Return encrypted rows ────► 11. Decrypt locally
                                                                    (reveals sender)
                                                         
                          ◄──── 12. DELETE fetched rows
```

---

## Implementation Checklist

### Phase 1: message_queue
- [ ] Create table with schema above
- [ ] Add indexes
- [ ] Enable RLS
- [ ] Run tests

### Phase 2: push_registrations  
- [ ] Create table with schema above
- [ ] Add indexes
- [ ] Enable RLS
- [ ] Run tests

### Phase 3: TTL Cleanup
- [ ] Create cleanup function
- [ ] Schedule with pg_cron or external trigger
- [ ] Run tests

### Phase 4: FCM Push Function
- [ ] Create Edge Function
- [ ] Set up database webhook on INSERT
- [ ] Configure FCM credentials
- [ ] Run tests

### Phase 5: RLS Policies
- [ ] Add SELECT policy for message_queue
- [ ] Add DELETE policy for message_queue
- [ ] Add INSERT policy for message_queue
- [ ] Add policies for push_registrations
- [ ] Run tests

### Phase 6: Validation
- [ ] Add check constraints to tables
- [ ] Test edge cases
- [ ] Document error responses

---

## Security Notes

1. **Server learns nothing about users**: Only sees opaque mailbox hashes that rotate daily
2. **No message content in pushes**: FCM payload contains only epoch timestamp
3. **Sealed sender**: Server cannot see who sent a message
4. **Short TTL**: Messages auto-delete after 7 days, push registrations after 25 hours
