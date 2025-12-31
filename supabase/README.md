# VOID Supabase Backend

This directory contains all the necessary files for setting up VOID's privacy-first messaging backend on Supabase.

## Directory Structure

```
supabase/
├── README.md                          # This file
├── QUICKSTART.md                      # Start here! Project setup & secrets
├── SETUP_INSTRUCTIONS.md              # Detailed guide for all phases
├── migrations/                        # SQL migration files (run in order)
│   ├── 01_message_queue.sql          # Phase 1: Message queue table
│   ├── 02_push_registrations.sql     # Phase 2: Push registrations table
│   ├── 03_ttl_cleanup.sql            # Phase 3: TTL cleanup job
│   ├── 04_push_webhook.sql           # Phase 4: Push notification webhook
│   ├── 05_rls_policies.sql           # Phase 5: Row-level security
│   └── 06_validation_constraints.sql # Phase 6: Data validation
└── functions/                         # Supabase Edge Functions
    └── send-push-notification/        # FCM push notification handler
        └── index.ts
```

## Quick Start

1. **[QUICKSTART.md](QUICKSTART.md)** - Create Supabase project & get secrets
2. **Run migrations** - Execute `01_*.sql` through `06_*.sql` in Supabase SQL Editor
3. **Deploy Edge Function** - `supabase functions deploy send-push-notification`
4. **Configure webhook** - Set up database trigger (see QUICKSTART.md step 7)
5. **Update Android app** - Add Supabase SDK and credentials

For detailed instructions, see **[SETUP_INSTRUCTIONS.md](SETUP_INSTRUCTIONS.md)**

## What Changed

### Removed
- `/server` directory (entire Ktor backend)
- Redis dependencies
- WebSocket server logic
- Manual FCM token management

### New Architecture
- **Supabase PostgreSQL**: Replaces Redis for message storage
- **Row-Level Security**: Database-enforced privacy controls
- **Edge Functions**: Serverless push notification handler
- **pg_cron**: Automatic cleanup of expired data
- **Database webhooks**: Trigger push notifications on message insert

## Privacy Features

1. **Blind mailboxes**: Server only sees cryptographic hashes, not identities
2. **Sealed sender**: Server cannot determine message sender
3. **No message content in pushes**: Only epoch timestamps sent via FCM
4. **Daily rotation**: Mailbox hashes change every 24 hours
5. **Automatic expiry**: Messages auto-delete after 7 days

## Key Concepts

### Message Queue
- Stores encrypted messages temporarily
- Indexed by `mailbox_hash` (blind identifier)
- Messages expire after 7 days (configurable)

### Push Registrations
- Links mailbox hashes to FCM tokens
- Registrations expire after 25 hours (daily rotation)
- Supports upsert for re-registration

### Silent Push Notifications
- No message content included
- Only contains epoch timestamp + random nonce
- Wakes app to fetch messages from queue

## Security Model

```
CLIENT                    SUPABASE                    FCM
──────                    ────────                    ───

1. Derive mailbox_hash
   (from private key)

2. Encrypt message
   (sealed sender)

3. INSERT message   ────► 4. Store in PostgreSQL
                          5. Trigger webhook
                          6. Lookup FCM token
                          7. Send silent push ────► 8. Wake app
                                                    9. Fetch messages ◄──── 10. SELECT by mailbox_hash
                                                   11. Decrypt locally
                                                   12. DELETE fetched ────►
```

## Database Tables

### `message_queue`
| Column | Type | Description |
|--------|------|-------------|
| id | uuid | Unique message ID |
| mailbox_hash | text | Recipient's blind mailbox (64 hex chars) |
| ciphertext | text | Base64 encrypted message |
| epoch | bigint | Unix timestamp (floored to 15s) |
| expires_at | timestamptz | Auto-delete after this time |
| created_at | timestamptz | Message creation time |

### `push_registrations`
| Column | Type | Description |
|--------|------|-------------|
| mailbox_hash | text | Blind mailbox identifier (PK) |
| fcm_token | text | Firebase Cloud Messaging token |
| expires_at | timestamptz | Auto-delete after 25 hours |
| created_at | timestamptz | Registration time |

## Testing

Each migration file includes test cases. Run them in the Supabase SQL Editor to verify:

```sql
-- Example test from 01_message_queue.sql
INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
VALUES ('abc123...', 'dGVzdA==', 1704067200);

SELECT * FROM public.message_queue
WHERE mailbox_hash = 'abc123...';
```

## Deployment

### Development
```bash
supabase init
supabase link --project-ref your-dev-project
supabase db push
supabase functions deploy send-push-notification
```

### Production
```bash
supabase link --project-ref your-prod-project
supabase db push
supabase functions deploy send-push-notification --no-verify-jwt
supabase secrets set FCM_SERVER_KEY=your-production-fcm-key
```

## Monitoring

### View Logs
```bash
# Edge Function logs
supabase functions logs send-push-notification --tail

# Database logs (via Supabase Dashboard)
Dashboard → Database → Logs
```

### Check Health
```sql
-- Messages in queue
SELECT count(*) FROM public.message_queue;

-- Active push registrations
SELECT count(*) FROM public.push_registrations;

-- Expired items (should be 0)
SELECT count(*) FROM public.message_queue WHERE expires_at < now();
SELECT count(*) FROM public.push_registrations WHERE expires_at < now();
```

## Cost Estimates

**Supabase Free Tier**:
- 500 MB database (enough for ~1M messages)
- 2 GB bandwidth/month
- 500K Edge Function calls/month

**For 1,000 active users**:
- ~10,000 messages/day = 300K/month
- ~100 MB storage
- ~30K push notifications/month
- **Fits comfortably in free tier**

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Push not arriving | Check Edge Function logs, verify FCM_SERVER_KEY |
| Messages not deleted | Verify cleanup job is running (pg_cron or Edge Function) |
| RLS blocking queries | Ensure client filters by `mailbox_hash` |
| Constraint violations | Check validation rules in `06_validation_constraints.sql` |

## Migration Checklist

- [x] Remove old `/server` directory
- [x] Remove server module from `settings.gradle.kts`
- [ ] Run Phase 1: message_queue table
- [ ] Run Phase 2: push_registrations table
- [ ] Run Phase 3: TTL cleanup setup
- [ ] Run Phase 4: Deploy Edge Function + webhook
- [ ] Run Phase 5: Configure RLS policies
- [ ] Run Phase 6: Add validation constraints
- [ ] Update Android app to use Supabase SDK
- [ ] Test end-to-end message flow
- [ ] Deploy to production

## Next Steps

1. Read `SETUP_INSTRUCTIONS.md` thoroughly
2. Set up a Supabase project (supabase.com)
3. Run all migrations in order (01-06)
4. Deploy the Edge Function
5. Update your Android app's network layer
6. Test with real devices
7. Monitor and optimize

## Resources

- [Supabase Documentation](https://supabase.com/docs)
- [Row-Level Security Guide](https://supabase.com/docs/guides/auth/row-level-security)
- [Edge Functions Guide](https://supabase.com/docs/guides/functions)
- [PostgreSQL Triggers](https://www.postgresql.org/docs/current/triggers.html)

---

**Questions?** See `SETUP_INSTRUCTIONS.md` for detailed guidance or consult the VOID implementation guide.
