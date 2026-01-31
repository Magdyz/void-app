-- =========================================================================
-- PHASE 15: FIX FUNCTION SEARCH_PATH SECURITY
-- =========================================================================
-- Sets explicit search_path for all functions to prevent potential
-- search_path manipulation attacks.
--
-- This is a non-breaking change that hardens security by ensuring
-- all functions explicitly resolve tables from the 'public' schema.
-- =========================================================================

-- =========================================================================
-- FROM 07_ephemeral_tokens.sql
-- =========================================================================

-- request_query_token (uses pgcrypto digest function from extensions schema)
CREATE OR REPLACE FUNCTION request_query_token(
    p_mailbox_hash TEXT,
    p_timestamp BIGINT,
    p_challenge TEXT
)
RETURNS TABLE(token_id UUID, expires_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
    v_token_id UUID;
    v_expires_at TIMESTAMPTZ;
    v_challenge_hash TEXT;
BEGIN
    -- Validate inputs
    IF length(p_mailbox_hash) != 64 OR p_mailbox_hash !~ '^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION 'Invalid mailbox_hash format';
    END IF;

    -- Check timestamp is within ±5 minutes (prevent replay attacks)
    IF ABS(p_timestamp - extract(epoch from now())) > 300 THEN
        RAISE EXCEPTION 'Timestamp too old or too far in future';
    END IF;

    -- Hash the challenge (store hash, not plaintext)
    v_challenge_hash := encode(digest(p_challenge, 'sha256'), 'hex');

    -- Insert token record
    v_expires_at := now() + INTERVAL '10 minutes';

    INSERT INTO public.ephemeral_tokens (mailbox_hash, challenge_hash, expires_at)
    VALUES (p_mailbox_hash, v_challenge_hash, v_expires_at)
    RETURNING id INTO v_token_id;

    -- Return token ID
    RETURN QUERY SELECT v_token_id, v_expires_at;
END;
$$;

-- validate_query_token
CREATE OR REPLACE FUNCTION validate_query_token(
    p_token_id UUID,
    p_mailbox_hash TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_valid BOOLEAN;
    v_token_record RECORD;
BEGIN
    -- Look up token
    SELECT * INTO v_token_record
    FROM public.ephemeral_tokens
    WHERE id = p_token_id
      AND mailbox_hash = p_mailbox_hash
      AND expires_at > now()
      AND used = false;

    -- Check if token found and valid
    IF FOUND THEN
        -- Mark token as used (single-use)
        UPDATE public.ephemeral_tokens
        SET used = true, used_at = now()
        WHERE id = p_token_id;

        RETURN true;
    ELSE
        RETURN false;
    END IF;
END;
$$;

-- cleanup_expired_tokens
CREATE OR REPLACE FUNCTION cleanup_expired_tokens()
RETURNS void
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    DELETE FROM public.ephemeral_tokens
    WHERE expires_at < now();

    RAISE NOTICE 'Cleaned up expired tokens at %', now();
END;
$$;

-- =========================================================================
-- FROM 08_rls_with_tokens.sql
-- =========================================================================

-- get_current_token_id
CREATE OR REPLACE FUNCTION get_current_token_id()
RETURNS UUID
LANGUAGE plpgsql
STABLE
SET search_path = public
AS $$
DECLARE
    v_token_header TEXT;
BEGIN
    -- Read X-Mailbox-Token header from request
    v_token_header := current_setting('request.headers', true)::json->>'x-mailbox-token';

    IF v_token_header IS NULL OR v_token_header = '' THEN
        RETURN NULL;
    END IF;

    -- Parse as UUID
    RETURN v_token_header::UUID;
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$;

-- =========================================================================
-- FROM 09_rate_limiting.sql
-- =========================================================================

-- check_rate_limit
CREATE OR REPLACE FUNCTION check_rate_limit(
    p_mailbox_hash TEXT,
    p_request_type TEXT,
    p_limit INTEGER
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
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
$$;

-- check_message_insert_rate_limit
CREATE OR REPLACE FUNCTION check_message_insert_rate_limit()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    -- Check rate limit: 100 messages per hour per mailbox
    PERFORM check_rate_limit(NEW.mailbox_hash, 'message_insert', 100);
    RETURN NEW;
END;
$$;

-- check_token_request_rate_limit
CREATE OR REPLACE FUNCTION check_token_request_rate_limit()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    -- Check rate limit: 50 token requests per hour per mailbox
    PERFORM check_rate_limit(NEW.mailbox_hash, 'token_request', 50);
    RETURN NEW;
END;
$$;

-- cleanup_rate_limit_tracker
CREATE OR REPLACE FUNCTION cleanup_rate_limit_tracker()
RETURNS void
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
    -- Delete records older than 2 hours (1 hour window + 1 hour buffer)
    DELETE FROM public.rate_limit_tracker
    WHERE window_end < (now() - INTERVAL '2 hours');

    RAISE NOTICE 'Cleaned up rate limit tracker at %', now();
END;
$$;

-- =========================================================================
-- FROM 06_validation_constraints.sql
-- =========================================================================

-- validate_message_epoch
CREATE OR REPLACE FUNCTION validate_message_epoch()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
  current_epoch BIGINT;
  one_hour_seconds BIGINT := 3600;
BEGIN
  current_epoch := extract(epoch from now())::bigint;

  IF NEW.epoch < (current_epoch - one_hour_seconds) THEN
    RAISE EXCEPTION 'Epoch % is too old (more than 1 hour in the past)', NEW.epoch;
  END IF;

  IF NEW.epoch > (current_epoch + one_hour_seconds) THEN
    RAISE EXCEPTION 'Epoch % is too far in the future (more than 1 hour ahead)', NEW.epoch;
  END IF;

  RETURN NEW;
END;
$$;

-- validate_message_expiry (from 11_fix_ttl_validation.sql - latest version)
CREATE OR REPLACE FUNCTION validate_message_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public
AS $$
BEGIN
  -- Minimum: Must be at least 1 day from now
  IF NEW.expires_at < (now() + INTERVAL '1 day') THEN
    RAISE EXCEPTION 'Expiry % is too soon (must be at least 1 day from now)', NEW.expires_at;
  END IF;

  -- Maximum: Allow up to 7 days + 1 hour to account for clock skew
  IF NEW.expires_at > (now() + INTERVAL '7 days 1 hour') THEN
    RAISE EXCEPTION 'Expiry % is too far (must be within 7 days from now)', NEW.expires_at;
  END IF;

  RETURN NEW;
END;
$$;

-- =========================================================================
-- FROM 03_ttl_cleanup.sql
-- =========================================================================

-- cleanup_expired_records
CREATE OR REPLACE FUNCTION public.cleanup_expired_records()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    -- Delete expired messages from message_queue
    DELETE FROM public.message_queue
    WHERE expires_at < now();

    -- Delete expired push registrations
    DELETE FROM public.push_registrations
    WHERE expires_at < now();

    -- Log cleanup (optional - remove in production if not needed)
    RAISE NOTICE 'Cleanup completed at %', now();
END;
$$;

-- =========================================================================
-- FROM 12_poisson_ghost_heartbeat.sql
-- =========================================================================

-- randomize_heartbeat_intervals
CREATE OR REPLACE FUNCTION randomize_heartbeat_intervals()
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    -- Update all existing registrations with random intervals
    UPDATE public.push_registrations
    SET
        heartbeat_interval_seconds = 600 + floor(random() * 600)::int,
        next_heartbeat_at = now() + (600 + floor(random() * 600)::int || ' seconds')::interval
    WHERE next_heartbeat_at IS NOT NULL;

    GET DIAGNOSTICS updated_count = ROW_COUNT;

    RAISE NOTICE 'Randomized heartbeat intervals for % registrations', updated_count;
    RETURN updated_count;
END;
$$;

-- get_due_heartbeats
CREATE OR REPLACE FUNCTION get_due_heartbeats()
RETURNS TABLE(
    mailbox_hash TEXT,
    fcm_token TEXT,
    heartbeat_interval_seconds INT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        pr.mailbox_hash,
        pr.fcm_token,
        pr.heartbeat_interval_seconds
    FROM public.push_registrations pr
    WHERE pr.next_heartbeat_at <= now()
    ORDER BY pr.next_heartbeat_at ASC
    LIMIT 100;
END;
$$;

-- update_next_heartbeat
CREATE OR REPLACE FUNCTION update_next_heartbeat(
    p_mailbox_hash TEXT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
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
$$;

-- =========================================================================
-- FROM 13_poisson_ghost_scheduler.sql
-- =========================================================================

-- trigger_heartbeat_sender
CREATE OR REPLACE FUNCTION trigger_heartbeat_sender()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    function_url TEXT;
    anon_key TEXT;
    response http_response;
BEGIN
    -- Get Supabase function URL from environment
    function_url := current_setting('app.supabase_function_url', true);
    anon_key := current_setting('app.supabase_anon_key', true);

    -- Skip if not configured (allows migration to succeed before deployment)
    IF function_url IS NULL OR function_url = '' THEN
        RAISE NOTICE 'Heartbeat sender not configured - skipping (set app.supabase_function_url)';
        RETURN;
    END IF;

    -- Call Edge Function via HTTP POST
    SELECT * INTO response FROM http((
        'POST',
        function_url,
        ARRAY[
            http_header('Content-Type', 'application/json'),
            http_header('Authorization', 'Bearer ' || anon_key)
        ],
        'application/json',
        '{}'
    )::http_request);

    -- Log result
    IF response.status = 200 THEN
        RAISE NOTICE 'Heartbeat sender completed: %', response.content;
    ELSE
        RAISE WARNING 'Heartbeat sender failed: HTTP % - %', response.status, response.content;
    END IF;
END;
$$;

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. All 15 functions now have explicit search_path = public
-- 2. This prevents potential search_path manipulation attacks
-- 3. No functional changes - behavior is identical
-- 4. CREATE OR REPLACE ensures existing function signatures are preserved
-- 5. Triggers remain intact (they reference the function, not its definition)
-- =========================================================================
