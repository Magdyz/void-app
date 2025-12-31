-- =========================================================================
-- PHASE 2: PUSH REGISTRATIONS TABLE
-- =========================================================================
-- Links blind mailbox hashes to FCM tokens for push notifications.
-- Mailboxes rotate daily, so clients re-register every 24 hours.
-- =========================================================================

-- Create the push_registrations table
CREATE TABLE IF NOT EXISTS public.push_registrations (
    mailbox_hash TEXT PRIMARY KEY CHECK (length(mailbox_hash) = 64),
    fcm_token TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '25 hours'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Create index for TTL cleanup
CREATE INDEX idx_push_registrations_expires_at ON public.push_registrations(expires_at);

-- Enable Row Level Security
ALTER TABLE public.push_registrations ENABLE ROW LEVEL SECURITY;

-- =========================================================================
-- TESTING PHASE 2
-- =========================================================================
-- Run these queries in Supabase SQL Editor to verify the setup:

-- Test 1: Upsert a registration
-- INSERT INTO public.push_registrations (mailbox_hash, fcm_token)
-- VALUES (
--     'test123test123test123test123test123test123test123test123test123te',
--     'fcm_token_abc_xyz_123'
-- )
-- ON CONFLICT (mailbox_hash)
-- DO UPDATE SET
--     fcm_token = EXCLUDED.fcm_token,
--     expires_at = now() + INTERVAL '25 hours',
--     created_at = now();

-- Test 2: Upsert same mailbox with different token (should update, not duplicate)
-- INSERT INTO public.push_registrations (mailbox_hash, fcm_token)
-- VALUES (
--     'test123test123test123test123test123test123test123test123test123te',
--     'fcm_token_updated_456'
-- )
-- ON CONFLICT (mailbox_hash)
-- DO UPDATE SET
--     fcm_token = EXCLUDED.fcm_token,
--     expires_at = now() + INTERVAL '25 hours',
--     created_at = now();

-- Test 3: Query by mailbox_hash (should return latest token)
-- SELECT * FROM public.push_registrations
-- WHERE mailbox_hash = 'test123test123test123test123test123test123test123test123test123te';

-- Test 4: Delete by mailbox_hash
-- DELETE FROM public.push_registrations
-- WHERE mailbox_hash = 'test123test123test123test123test123test123test123test123test123te';

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. mailbox_hash is PRIMARY KEY (one registration per mailbox)
-- 2. UPSERT support via ON CONFLICT for daily re-registration
-- 3. RLS policies will be added in Phase 5
-- 4. TTL cleanup will be added in Phase 3
-- 5. Registrations expire after 25 hours (slightly longer than 24h for clock drift)
-- 6. mailbox_hash must be exactly 64 hex characters (32 bytes in hex encoding)
