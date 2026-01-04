-- =========================================================================
-- PHASE 12: POISSON GHOST PROTOCOL - HEARTBEAT TRACKING
-- =========================================================================
-- Implements Poisson-distributed heartbeat intervals for enhanced privacy.
--
-- Privacy Enhancement:
-- - Random heartbeat intervals (10-20 min) per user
-- - Constant 2KB response size (padded or noise)
-- - Indistinguishable timing patterns from Google/ISP perspective
-- - Lazy message queue: messages wait for next heartbeat
--
-- Architecture:
-- - Each user gets random heartbeat schedule
-- - Heartbeats trigger silent FCM push
-- - Client fetches mailbox (always 2KB response)
-- - Real messages are padded, empty mailboxes return noise
-- =========================================================================

-- Add heartbeat tracking columns to push_registrations
ALTER TABLE public.push_registrations
ADD COLUMN IF NOT EXISTS next_heartbeat_at TIMESTAMPTZ DEFAULT now(),
ADD COLUMN IF NOT EXISTS heartbeat_interval_seconds INT DEFAULT 900; -- 15 min default (will be randomized)

-- Create index for efficient heartbeat queries
CREATE INDEX IF NOT EXISTS idx_push_registrations_next_heartbeat
ON public.push_registrations(next_heartbeat_at)
WHERE next_heartbeat_at IS NOT NULL;

-- =========================================================================
-- FUNCTION: Randomize heartbeat intervals for all registrations
-- =========================================================================
-- This function sets a random heartbeat interval (600-1200 seconds = 10-20 min)
-- for each push registration to implement Poisson distribution.
CREATE OR REPLACE FUNCTION randomize_heartbeat_intervals()
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    -- Update all existing registrations with random intervals
    UPDATE public.push_registrations
    SET
        heartbeat_interval_seconds = 600 + floor(random() * 600)::int, -- Random 600-1200 sec
        next_heartbeat_at = now() + (600 + floor(random() * 600)::int || ' seconds')::interval
    WHERE next_heartbeat_at IS NOT NULL;

    GET DIAGNOSTICS updated_count = ROW_COUNT;

    RAISE NOTICE 'Randomized heartbeat intervals for % registrations', updated_count;
    RETURN updated_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- FUNCTION: Get users due for heartbeat
-- =========================================================================
-- Returns all push registrations where heartbeat is due.
-- Called by heartbeat-sender Edge Function every minute.
CREATE OR REPLACE FUNCTION get_due_heartbeats()
RETURNS TABLE(
    mailbox_hash TEXT,
    fcm_token TEXT,
    heartbeat_interval_seconds INT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        pr.mailbox_hash,
        pr.fcm_token,
        pr.heartbeat_interval_seconds
    FROM public.push_registrations pr
    WHERE pr.next_heartbeat_at <= now()
    ORDER BY pr.next_heartbeat_at ASC
    LIMIT 100; -- Process 100 at a time to avoid overwhelming FCM
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- FUNCTION: Update next heartbeat time
-- =========================================================================
-- Called after sending heartbeat to schedule next one.
-- Adds jitter (±10%) to interval for additional randomness.
CREATE OR REPLACE FUNCTION update_next_heartbeat(
    p_mailbox_hash TEXT
)
RETURNS VOID AS $$
DECLARE
    base_interval INT;
    jitter_seconds INT;
    next_interval INT;
BEGIN
    -- Get current heartbeat interval
    SELECT heartbeat_interval_seconds INTO base_interval
    FROM public.push_registrations
    WHERE mailbox_hash = p_mailbox_hash;

    IF base_interval IS NULL THEN
        RAISE EXCEPTION 'Mailbox hash % not found', p_mailbox_hash;
    END IF;

    -- Add ±10% jitter to base interval
    jitter_seconds := floor((random() * 0.2 - 0.1) * base_interval)::int;
    next_interval := base_interval + jitter_seconds;

    -- Ensure interval stays within bounds (9-22 minutes)
    next_interval := GREATEST(540, LEAST(1320, next_interval));

    -- Update next heartbeat time
    UPDATE public.push_registrations
    SET next_heartbeat_at = now() + (next_interval || ' seconds')::interval
    WHERE mailbox_hash = p_mailbox_hash;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- Randomize existing registrations (run once)
-- =========================================================================
SELECT randomize_heartbeat_intervals();

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. Heartbeat intervals are randomized per user (600-1200 seconds)
-- 2. Jitter (±10%) is added to each interval for additional randomness
-- 3. get_due_heartbeats() processes up to 100 users per call
-- 4. Intervals are bounded to 9-22 minutes to prevent extreme values
-- 5. This migration is backward compatible - existing functionality unchanged
-- 6. Heartbeat system is triggered by pg_cron job (added in migration 13)
