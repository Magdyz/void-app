-- =========================================================================
-- PHASE 17: ZERO TRUST UPDATE - CLEAN OLD DATA
-- =========================================================================
-- After migrating to the zero trust model with instant destruction,
-- we need to clear old data from previous schema versions.
--
-- This migration:
-- 1. Clears message_queue (old 7-day TTL messages incompatible with 24h model)
-- 2. Clears push_registrations (clients will re-register on next launch)
-- 3. Clears ephemeral_tokens (short-lived anyway, clean slate)
-- 4. Clears rate_limit_tracker (clean rate limit history)
--
-- PRESERVED:
-- - auth.users (Supabase user accounts)
-- - All schema/functions/triggers/policies remain intact
--
-- IMPORTANT: Run this AFTER migration 16_instant_destruction.sql
-- =========================================================================

-- =========================================================================
-- 1. CLEAR MESSAGE QUEUE
-- =========================================================================
-- Old messages were stored with 7-day TTL. The new zero trust model uses
-- 24-hour TTL with instant destruction on delivery ACK. Old messages are
-- incompatible with the new model and should be cleared.

TRUNCATE TABLE public.message_queue;

-- Log the action
DO $$
BEGIN
    RAISE NOTICE '[ZERO_TRUST_CLEANUP] Cleared message_queue - old 7-day TTL messages removed';
END $$;

-- =========================================================================
-- 2. CLEAR PUSH REGISTRATIONS
-- =========================================================================
-- Old push registrations may have stale FCM tokens or missing heartbeat
-- columns. Clients will re-register on next app launch.

TRUNCATE TABLE public.push_registrations;

-- Log the action
DO $$
BEGIN
    RAISE NOTICE '[ZERO_TRUST_CLEANUP] Cleared push_registrations - clients will re-register';
END $$;

-- =========================================================================
-- 3. CLEAR EPHEMERAL TOKENS
-- =========================================================================
-- These are short-lived query tokens (10-minute lifetime).
-- Starting fresh ensures no orphaned or expired tokens.

TRUNCATE TABLE public.ephemeral_tokens;

-- Log the action
DO $$
BEGIN
    RAISE NOTICE '[ZERO_TRUST_CLEANUP] Cleared ephemeral_tokens - clean token slate';
END $$;

-- =========================================================================
-- 4. CLEAR RATE LIMIT TRACKER
-- =========================================================================
-- Rate limit history from old schema version.
-- Starting fresh with clean rate limiting.

TRUNCATE TABLE public.rate_limit_tracker;

-- Log the action
DO $$
BEGIN
    RAISE NOTICE '[ZERO_TRUST_CLEANUP] Cleared rate_limit_tracker - fresh rate limits';
END $$;

-- =========================================================================
-- VERIFICATION QUERIES
-- =========================================================================
-- Run these after migration to verify cleanup:
--
-- SELECT COUNT(*) FROM public.message_queue;        -- Should be 0
-- SELECT COUNT(*) FROM public.push_registrations;   -- Should be 0
-- SELECT COUNT(*) FROM public.ephemeral_tokens;     -- Should be 0
-- SELECT COUNT(*) FROM public.rate_limit_tracker;   -- Should be 0
--
-- Verify users are preserved:
-- SELECT COUNT(*) FROM auth.users;                  -- Should be unchanged
-- =========================================================================

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. This is a ONE-TIME migration for the zero trust update
-- 2. All clients must re-authenticate and re-register for push
-- 3. Message history is intentionally not preserved (zero trust principle)
-- 4. auth.users table is NOT affected - user accounts remain intact
-- 5. All schema, functions, triggers, and policies are preserved
-- =========================================================================
