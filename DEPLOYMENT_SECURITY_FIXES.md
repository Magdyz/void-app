# Security Fixes Deployment Guide

## Overview

This guide covers the deployment of two critical security fixes for the VOID messaging app:
1. **Ephemeral Query Tokens** - Fixes broken RLS policies by requiring cryptographic proof-of-ownership
2. **Rate Limiting** - Adds database-level rate limits to prevent spam and DoS attacks

**Timeline**: 3-4 days (staged deployment)
**Breaking Change**: Yes - requires app version 1.6.0+
**Rollback**: Supported (see Rollback section)

---

## Pre-Deployment Checklist

- [ ] All code changes merged to `main` branch
- [ ] Unit tests pass
- [ ] Integration tests pass (token request, message fetch, rate limiting)
- [ ] Supabase project accessible (production environment)
- [ ] Database backup created
- [ ] Rollback SQL scripts prepared
- [ ] Team notified of deployment schedule
- [ ] Maintenance window scheduled (if needed for Phase 4)

---

## Phase 1: Database Migrations (Day 1) - NON-BREAKING

### Step 1.1: Deploy Migrations

```bash
cd /Users/magz/Documents/Coding/void-app/supabase

# Deploy migrations 07, 09, 10 (skip 08 for now)
supabase db push
```

**What gets deployed**:
- Migration 07: `ephemeral_tokens` table + token request/validation functions
- Migration 09: `rate_limit_tracker` table + rate limiting triggers
- Migration 10: pg_cron scheduled cleanup jobs

### Step 1.2: Verify Migration Success

```sql
-- Connect to Supabase SQL Editor

-- 1. Verify tables exist
SELECT COUNT(*) FROM public.ephemeral_tokens;
SELECT COUNT(*) FROM public.rate_limit_tracker;

-- 2. Test token request function
SELECT * FROM request_query_token(
    'a3f5c2e1b4d8f9a7c3e5d2f1b8a4c6e9a3f5c2e1b4d8f9a7c3e5d2f1b8a4c6e9',
    extract(epoch from now())::bigint,
    'test_challenge_hmac_string_here_64_chars_long_example_test_abc123'
);
-- Should return: { token_id: UUID, expires_at: timestamp }

-- 3. Test token validation function
SELECT validate_query_token(
    '<token_id_from_above>'::uuid,
    'a3f5c2e1b4d8f9a7c3e5d2f1b8a4c6e9a3f5c2e1b4d8f9a7c3e5d2f1b8a4c6e9'
);
-- Should return: true (first call), false (second call - single-use)

-- 4. Verify pg_cron jobs scheduled
SELECT jobname, schedule, command FROM cron.job;
-- Should show: cleanup-expired-tokens, cleanup-rate-limit-tracker

-- 5. Test rate limiting trigger
-- Try inserting 101 messages to same mailbox
DO $$
DECLARE
    test_mailbox TEXT := 'a' || repeat('0', 63); -- 64-char test mailbox
BEGIN
    FOR i IN 1..101 LOOP
        BEGIN
            INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
            VALUES (test_mailbox, 'test_message', extract(epoch from now())::bigint);

            IF i = 101 THEN
                RAISE EXCEPTION 'Rate limit did not trigger at 101st message!';
            END IF;
        EXCEPTION
            WHEN OTHERS THEN
                IF i = 101 AND SQLERRM LIKE '%Rate limit exceeded%' THEN
                    RAISE NOTICE 'Rate limit correctly triggered at message %', i;
                    EXIT; -- Success!
                ELSIF i < 101 THEN
                    RAISE; -- Unexpected error before limit
                END IF;
        END;
    END LOOP;

    -- Cleanup test data
    DELETE FROM public.message_queue WHERE mailbox_hash = test_mailbox;
    DELETE FROM public.rate_limit_tracker WHERE mailbox_hash = test_mailbox;
END $$;
```

### Step 1.3: Success Criteria

✅ All tables created
✅ Functions callable without errors
✅ Rate limit triggers block 101st message
✅ Cron jobs scheduled
✅ Token request returns valid UUID
✅ Token validation works (returns true, then false)

**If any test fails**: Stop deployment, investigate issue, fix and redeploy.

---

## Phase 2: Client Implementation (Day 2-3)

### Step 2.1: Update All Call Sites

The following files have been updated and need testing:

1. **EphemeralTokenManager.kt** (NEW)
   - Test HMAC generation
   - Test token caching
   - Test token expiry detection

2. **MessageFetcher.kt** (MODIFIED)
   - Update all call sites to pass `identitySeed`
   - Verify token header is sent
   - Test with valid and expired tokens

3. **NetworkModule.kt** (MODIFIED)
   - Verify dependency injection wiring
   - Test that `EphemeralTokenManager` is injected correctly

4. **MessageSender.kt** (MODIFIED)
   - Test rate limit exception handling
   - Verify user-friendly error messages

### Step 2.2: Find and Update Call Sites

```bash
# Find all usages of MessageFetcher.fetchMessages()
grep -r "fetchMessages(" --include="*.kt" blocks/ slate/

# Update each call site to pass identitySeed parameter
# BEFORE: fetcher.fetchMessages(mailboxHashes, epoch)
# AFTER:  fetcher.fetchMessages(identitySeed, mailboxHashes, epoch)
```

**Common call sites to update**:
- `MessageRepository.kt` - `syncMessages()` function
- `ChatViewModel.kt` - Message polling logic
- Any background sync workers

### Step 2.3: Build and Test

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

### Step 2.4: Manual Testing Checklist

- [ ] App launches without crashes
- [ ] Token manager generates HMAC correctly
- [ ] Token request succeeds (check logs for "Token acquired")
- [ ] Message fetch uses token header (check network logs)
- [ ] Cached tokens reused (only 1 request per 10 minutes)
- [ ] Message sending works normally
- [ ] Rate limit exception handled gracefully (try sending 101 messages)

---

## Phase 3: Gradual Rollout (Day 3)

### Step 3.1: Version Bump

```kotlin
// In app/build.gradle.kts
android {
    defaultConfig {
        versionCode = 16  // Increment
        versionName = "1.6.0"  // Major version bump
    }
}
```

### Step 3.2: Build Release APK

```bash
# Build release APK with signing
./gradlew assembleRelease

# Verify APK
./gradlew bundleRelease
```

### Step 3.3: Staged Rollout Plan

| Stage | Audience | Duration | Monitoring |
|-------|----------|----------|------------|
| Alpha | Internal team (10 users) | 24 hours | Manual testing + logs |
| Beta | Beta testers (100 users) | 12 hours | Firebase Crashlytics + metrics |
| Rollout 10% | General users | 6 hours | Token success rate, errors |
| Rollout 50% | General users | 12 hours | Full monitoring |
| Rollout 100% | All users | 24 hours | Stability metrics |

### Step 3.4: Monitoring Metrics

**Key Metrics to Track**:
```
Token System:
- Token request success rate (target: >99%)
- Token request latency (target: <100ms)
- Token cache hit rate (target: >90%)
- Token validation failures (target: <1%)

Message Operations:
- Message fetch success rate (target: >99%)
- Message send success rate (target: >99%)
- Rate limit violations per hour (target: <1% of users)

Performance:
- App crash rate (target: <0.1%)
- Message fetch latency (target: <500ms)
```

### Step 3.5: Rollback Triggers

**Automatic rollback if**:
- Token request failure rate >5%
- App crash rate >1%
- Message fetch failure rate >5%

**Manual rollback if**:
- Critical bug reports >10
- User complaints >50
- Database performance degrades

---

## Phase 4: Activate Secure RLS (Day 4) - BREAKING CHANGE

### ⚠️ WARNING: This Phase Breaks Old Clients

**Pre-activation Checklist**:
- [ ] 95%+ users on version 1.6.0+
- [ ] Token system tested in production (Phase 3)
- [ ] Zero critical issues from Phase 3
- [ ] Rollback SQL prepared and tested
- [ ] Maintenance window scheduled (optional)
- [ ] Team on standby for emergency response

### Step 4.1: Deploy Migration 08

```bash
cd /Users/magz/Documents/Coding/void-app/supabase

# Deploy the breaking change migration
supabase db push --include-all
```

**What changes**:
- RLS policies updated to require `validate_query_token()`
- Old queries without tokens will fail with RLS violation
- Clients on v1.5.x and below cannot fetch messages

### Step 4.2: Immediate Verification

```sql
-- Test that old requests fail (expected)
SELECT * FROM public.message_queue LIMIT 1;
-- Should return: 0 rows (RLS blocks without token)

-- Test that requests with valid token succeed
-- (Use a token from Phase 3 testing)
```

### Step 4.3: Post-Deployment Monitoring

**First 1 Hour**:
- Monitor Supabase logs for RLS violations
  - Expected: Some violations from old clients (declining over time)
  - Unexpected: Violations from v1.6.0+ clients
- Check token validation latency (target: <50ms p95)
- Monitor message fetch success rate

**Next 24 Hours**:
- Track old client usage (should drop to 0%)
- Monitor for any unexpected errors
- Check rate limit hit frequency

### Step 4.4: Success Criteria

✅ Zero RLS violations from clients on v1.6.0+
✅ All message fetches succeed for updated clients
✅ Old clients blocked (expected behavior)
✅ Token validation latency <50ms (p95)
✅ No critical bugs reported

---

## Rollback Procedures

### Emergency Rollback (Migration 08)

**Symptoms**:
- High rate of RLS violations from v1.6.0+ clients
- Message fetch failures
- Critical bug in token system

**Steps**:

```sql
-- IMMEDIATE ROLLBACK: Revert to insecure RLS policies
-- Run this in Supabase SQL Editor

-- Drop secure policies
DROP POLICY IF EXISTS "Select messages with valid token" ON public.message_queue;
DROP POLICY IF EXISTS "Delete messages with valid token" ON public.message_queue;
DROP POLICY IF EXISTS "Select push registration with valid token" ON public.push_registrations;
DROP POLICY IF EXISTS "Update push registration with valid token" ON public.push_registrations;
DROP POLICY IF EXISTS "Delete push registration with valid token" ON public.push_registrations;

-- Restore old insecure policies
CREATE POLICY "Select messages by mailbox_hash"
ON public.message_queue
FOR SELECT
TO anon, authenticated
USING (true);

CREATE POLICY "Delete messages by mailbox_hash"
ON public.message_queue
FOR DELETE
TO anon, authenticated
USING (true);

CREATE POLICY "Select push registrations"
ON public.push_registrations
FOR SELECT
TO anon, authenticated
USING (true);

CREATE POLICY "Update push registrations"
ON public.push_registrations
FOR UPDATE
TO anon, authenticated
USING (true);

CREATE POLICY "Delete push registrations"
ON public.push_registrations
FOR DELETE
TO anon, authenticated
USING (true);

-- Verify rollback
SELECT * FROM public.message_queue LIMIT 1;
-- Should now return rows without needing token
```

**Impact**: Reverts to insecure state (acceptable for emergency)
**Duration**: 30 seconds to execute
**Next Steps**: Investigate issue, fix in development, redeploy Phase 4 when ready

### Disable Rate Limiting (If Too Aggressive)

**Symptoms**: Legitimate users frequently hitting rate limits

**Steps**:

```sql
-- Disable rate limit triggers temporarily
DROP TRIGGER IF EXISTS rate_limit_message_insert ON public.message_queue;
DROP TRIGGER IF EXISTS rate_limit_token_request ON public.ephemeral_tokens;

-- Verify triggers removed
SELECT tgname FROM pg_trigger WHERE tgrelid = 'public.message_queue'::regclass;
-- Should not show rate_limit_message_insert
```

**Impact**: No rate limiting for 1-6 hours (spam risk)
**Next Steps**:
1. Analyze rate limit hit patterns
2. Adjust limits (100 → 200, 50 → 100) in migration 09
3. Re-enable triggers with new limits

---

## Testing Checklist

### Before Migration 08 Deployment

- [ ] Token request function works (SQL test)
- [ ] Token validation function works (SQL test)
- [ ] Rate limit triggers block excess messages
- [ ] Client can request token via RPC
- [ ] Client can fetch messages with token header
- [ ] Token caching reduces requests
- [ ] Rate limit exception handled in UI
- [ ] Old token requests rejected (expired timestamp)
- [ ] Used tokens rejected (single-use)

### After Migration 08 Deployment

- [ ] New clients (v1.6.0+) can fetch messages
- [ ] Old clients blocked (RLS violation - expected)
- [ ] No RLS violations from updated clients
- [ ] Token validation latency <50ms (p95)
- [ ] Message send latency <100ms (p95)
- [ ] Cron jobs running (check `SELECT * FROM cron.job_run_details;`)
- [ ] No critical errors in logs

---

## Troubleshooting

### Issue: Token Request Returns "Timestamp too old"

**Cause**: Client clock skewed by >5 minutes
**Fix**: Increase tolerance in `request_query_token()` to ±10 minutes (line 77 in migration 07)

### Issue: Rate Limit Hit Unexpectedly

**Cause**: Limit too low for normal usage
**Fix**: Adjust limits in migration 09 (line 43: 100 → 200, line 57: 50 → 100)

### Issue: "Header X-Mailbox-Token not found"

**Cause**: Supabase not reading custom headers
**Fix**: Check header name matches `get_current_token_id()` function (migration 08, line 30)

### Issue: Token Validation Always Returns False

**Cause**: Token marked as "used" on first validation
**Fix**: Check RLS policy isn't validating token twice (should only call `validate_query_token()` once per query)

---

## Monitoring Dashboard

### Recommended Metrics (Firebase/Grafana)

```
Token System Health:
- token_request_success_rate (gauge)
- token_request_latency_ms (histogram)
- token_cache_hit_rate (gauge)
- token_validation_failures (counter)

Rate Limiting:
- rate_limit_violations_per_hour (counter by mailbox_hash)
- rate_limit_false_positives (counter)

Database Performance:
- ephemeral_tokens_table_size_mb (gauge)
- rate_limit_tracker_table_size_mb (gauge)
- rls_policy_execution_time_ms (histogram)
```

---

## Post-Deployment Tasks

- [ ] Monitor metrics for 7 days
- [ ] Review rate limit hit patterns
- [ ] Optimize token cache TTL if needed
- [ ] Document any issues encountered
- [ ] Update runbook with lessons learned
- [ ] Schedule follow-up review meeting
- [ ] Archive deployment logs
- [ ] Update API documentation

---

## Summary

| Phase | Duration | Risk | Can Rollback? |
|-------|----------|------|---------------|
| Phase 1: DB Migrations | 4 hours | LOW | Yes (no impact) |
| Phase 2: Client Code | 16 hours | MEDIUM | Yes (no deployment yet) |
| Phase 3: Gradual Rollout | 24 hours | MEDIUM | Yes (rollback app version) |
| Phase 4: Activate RLS | 4 hours | HIGH | Yes (SQL rollback in 30s) |

**Total Timeline**: 3-4 days
**Total Effort**: ~35 hours
**Breaking Change**: Yes (requires v1.6.0+)
**Rollback Time**: <5 minutes (emergency SQL script)

---

## Support Contacts

- **Database Issues**: DBA team
- **Client Issues**: Mobile dev team
- **Security Review**: Security team
- **On-Call**: DevOps rotation

---

## Appendix: File Changes Summary

### New Files Created
1. `supabase/migrations/07_ephemeral_tokens.sql`
2. `supabase/migrations/08_rls_with_tokens.sql`
3. `supabase/migrations/09_rate_limiting.sql`
4. `supabase/migrations/10_scheduled_jobs.sql`
5. `slate/network/.../auth/EphemeralTokenManager.kt`
6. `slate/network/.../exceptions/RateLimitException.kt`

### Files Modified
1. `slate/network/.../supabase/MessageFetcher.kt`
2. `slate/network/.../supabase/MessageSender.kt`
3. `slate/network/.../di/NetworkModule.kt`
4. `blocks/messaging/.../MessageRepository.kt` (pending)

### Database Schema Changes
- New tables: `ephemeral_tokens`, `rate_limit_tracker`
- New functions: `request_query_token()`, `validate_query_token()`, `check_rate_limit()`
- New triggers: `rate_limit_message_insert`, `rate_limit_token_request`
- Updated RLS policies: `message_queue`, `push_registrations`

---

**Document Version**: 1.0
**Last Updated**: 2026-01-04
**Author**: VOID Security Team
