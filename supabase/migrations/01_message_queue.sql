-- =========================================================================
-- PHASE 1: MESSAGE QUEUE TABLE
-- =========================================================================
-- This table stores encrypted messages until recipients fetch them.
-- Server never sees message content, only opaque mailbox hashes.
-- =========================================================================

-- Create the message_queue table
CREATE TABLE IF NOT EXISTS public.message_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mailbox_hash TEXT NOT NULL CHECK (length(mailbox_hash) = 64),
    ciphertext TEXT NOT NULL,
    epoch BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '7 days'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Create indexes for efficient queries
CREATE INDEX idx_message_queue_mailbox_hash ON public.message_queue(mailbox_hash);
CREATE INDEX idx_message_queue_expires_at ON public.message_queue(expires_at);
CREATE INDEX idx_message_queue_epoch ON public.message_queue(epoch);

-- Enable Row Level Security
ALTER TABLE public.message_queue ENABLE ROW LEVEL SECURITY;

-- =========================================================================
-- TESTING PHASE 1
-- =========================================================================
-- Run these queries in Supabase SQL Editor to verify the setup:

-- Test 1: Insert a test row
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     'dGVzdCBjaXBoZXJ0ZXh0',
--     1704067200
-- );

-- Test 2: Query by mailbox_hash (should return the row)
-- SELECT * FROM public.message_queue
-- WHERE mailbox_hash = 'abc123def456abc123def456abc123def456abc123def456abc123def456abcd';

-- Test 3: Query with wrong mailbox_hash (should return nothing after RLS is configured)
-- SELECT * FROM public.message_queue
-- WHERE mailbox_hash = 'wrong_hash';

-- Test 4: Delete the test row
-- DELETE FROM public.message_queue
-- WHERE mailbox_hash = 'abc123def456abc123def456abc123def456abc123def456abc123def456abcd';

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. RLS policies will be added in Phase 5
-- 2. TTL cleanup will be added in Phase 3
-- 3. mailbox_hash must be exactly 64 hex characters (32 bytes in hex encoding)
-- 4. ciphertext is base64-encoded encrypted message blob
-- 5. epoch is Unix timestamp (seconds), floored to 15s intervals
-- 6. Messages auto-expire after 7 days by default
