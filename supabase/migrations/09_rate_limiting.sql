-- =========================================================================
-- PHASE 9: RATE LIMITING
-- =========================================================================
-- Prevents spam and DoS attacks via database-level triggers.
-- Uses sliding window algorithm for accurate rate limiting.
-- =========================================================================

-- =========================================================================
-- RATE LIMIT TRACKING TABLE
-- =========================================================================

CREATE TABLE IF NOT EXISTS public.rate_limit_tracker (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mailbox_hash TEXT NOT NULL CHECK (length(mailbox_hash) = 64 AND mailbox_hash ~ '^[a-f0-9]{64}$'),
    request_type TEXT NOT NULL CHECK (request_type IN ('message_insert', 'token_request')),
    request_count INTEGER NOT NULL DEFAULT 1,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Composite index for fast lookups
CREATE INDEX idx_rate_limit_tracker_lookup
ON public.rate_limit_tracker(mailbox_hash, request_type, window_end);

-- Index for TTL cleanup
CREATE INDEX idx_rate_limit_tracker_window_end
ON public.rate_limit_tracker(window_end);

-- Enable RLS (no policies needed - triggers manage access)
ALTER TABLE public.rate_limit_tracker ENABLE ROW LEVEL SECURITY;

-- =========================================================================
-- RATE LIMIT CHECK FUNCTION (Sliding Window)
-- =========================================================================

CREATE OR REPLACE FUNCTION check_rate_limit(
    p_mailbox_hash TEXT,
    p_request_type TEXT,
    p_limit INTEGER
)
RETURNS BOOLEAN AS $$
DECLARE
    v_window_start TIMESTAMPTZ;
    v_window_end TIMESTAMPTZ;
    v_current_count INTEGER;
BEGIN
    -- Define 1-hour sliding window
    v_window_end := now();
    v_window_start := v_window_end - INTERVAL '1 hour';

    -- Count requests in the past hour
    SELECT COALESCE(SUM(request_count), 0)
    INTO v_current_count
    FROM public.rate_limit_tracker
    WHERE mailbox_hash = p_mailbox_hash
      AND request_type = p_request_type
      AND window_end > v_window_start;

    -- Check if limit exceeded
    IF v_current_count >= p_limit THEN
        RAISE EXCEPTION 'Rate limit exceeded for %: % requests in past hour (limit: %)',
            p_request_type, v_current_count, p_limit
            USING ERRCODE = 'P0001';
    END IF;

    -- Record this request
    INSERT INTO public.rate_limit_tracker (
        mailbox_hash,
        request_type,
        request_count,
        window_start,
        window_end
    ) VALUES (
        p_mailbox_hash,
        p_request_type,
        1,
        v_window_start,
        v_window_end
    );

    RETURN true;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- MESSAGE INSERT RATE LIMIT TRIGGER
-- =========================================================================

CREATE OR REPLACE FUNCTION check_message_insert_rate_limit()
RETURNS TRIGGER AS $$
BEGIN
    -- Check rate limit: 100 messages per hour per mailbox
    PERFORM check_rate_limit(NEW.mailbox_hash, 'message_insert', 100);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS rate_limit_message_insert ON public.message_queue;

CREATE TRIGGER rate_limit_message_insert
    BEFORE INSERT ON public.message_queue
    FOR EACH ROW
    EXECUTE FUNCTION check_message_insert_rate_limit();

-- =========================================================================
-- TOKEN REQUEST RATE LIMIT TRIGGER
-- =========================================================================

CREATE OR REPLACE FUNCTION check_token_request_rate_limit()
RETURNS TRIGGER AS $$
BEGIN
    -- Check rate limit: 50 token requests per hour per mailbox
    PERFORM check_rate_limit(NEW.mailbox_hash, 'token_request', 50);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS rate_limit_token_request ON public.ephemeral_tokens;

CREATE TRIGGER rate_limit_token_request
    BEFORE INSERT ON public.ephemeral_tokens
    FOR EACH ROW
    EXECUTE FUNCTION check_token_request_rate_limit();

-- =========================================================================
-- TTL CLEANUP FOR RATE LIMIT TRACKER
-- =========================================================================

CREATE OR REPLACE FUNCTION cleanup_rate_limit_tracker()
RETURNS void AS $$
BEGIN
    -- Delete records older than 2 hours (1 hour window + 1 hour buffer)
    DELETE FROM public.rate_limit_tracker
    WHERE window_end < (now() - INTERVAL '2 hours');

    RAISE NOTICE 'Cleaned up rate limit tracker at %', now();
END;
$$ LANGUAGE plpgsql;

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. Rate limits are enforced at database level via triggers
-- 2. Uses sliding window algorithm for accurate counting
-- 3. Message inserts: 100/hour per mailbox
-- 4. Token requests: 50/hour per mailbox (implicitly limits queries)
-- 5. Cleanup runs hourly to remove old tracking records
-- 6. Error code P0001 (RAISE_EXCEPTION) for rate limit violations
