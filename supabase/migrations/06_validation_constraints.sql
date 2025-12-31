-- =========================================================================
-- PHASE 6: VALIDATION CONSTRAINTS & SECURITY HARDENING
-- =========================================================================
-- Add check constraints to validate input data and prevent abuse.
-- Ensures data integrity and enforces business rules at the database level.
-- =========================================================================

-- =========================================================================
-- MESSAGE_QUEUE VALIDATION
-- =========================================================================

-- Validate mailbox_hash is exactly 64 hex characters
-- (Already added in Phase 1, but reinforced here for clarity)
ALTER TABLE public.message_queue
DROP CONSTRAINT IF EXISTS message_queue_mailbox_hash_check;

ALTER TABLE public.message_queue
ADD CONSTRAINT message_queue_mailbox_hash_check
CHECK (
  length(mailbox_hash) = 64 AND
  mailbox_hash ~ '^[a-f0-9]{64}$'  -- Must be 64 lowercase hex characters
);

-- Validate ciphertext is not empty and has reasonable size limit (64KB)
ALTER TABLE public.message_queue
DROP CONSTRAINT IF EXISTS message_queue_ciphertext_check;

ALTER TABLE public.message_queue
ADD CONSTRAINT message_queue_ciphertext_check
CHECK (
  length(ciphertext) > 0 AND
  length(ciphertext) <= 87400  -- Base64 encoded 64KB ≈ 87,400 chars
);

-- Validate epoch is reasonable (within ±1 hour of current time)
-- Note: This is enforced via trigger since it needs to check against now()
CREATE OR REPLACE FUNCTION validate_message_epoch()
RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS validate_epoch_on_insert ON public.message_queue;

CREATE TRIGGER validate_epoch_on_insert
  BEFORE INSERT ON public.message_queue
  FOR EACH ROW
  EXECUTE FUNCTION validate_message_epoch();

-- Validate expires_at is between 1-7 days from now
CREATE OR REPLACE FUNCTION validate_message_expiry()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.expires_at < (now() + INTERVAL '1 day') THEN
    RAISE EXCEPTION 'Expiry % is too soon (must be at least 1 day from now)', NEW.expires_at;
  END IF;

  IF NEW.expires_at > (now() + INTERVAL '7 days') THEN
    RAISE EXCEPTION 'Expiry % is too far (must be within 7 days from now)', NEW.expires_at;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS validate_expiry_on_insert ON public.message_queue;

CREATE TRIGGER validate_expiry_on_insert
  BEFORE INSERT ON public.message_queue
  FOR EACH ROW
  EXECUTE FUNCTION validate_message_expiry();

-- =========================================================================
-- PUSH_REGISTRATIONS VALIDATION
-- =========================================================================

-- Validate mailbox_hash is exactly 64 hex characters
-- (Already added in Phase 2, but reinforced here for clarity)
ALTER TABLE public.push_registrations
DROP CONSTRAINT IF EXISTS push_registrations_mailbox_hash_check;

ALTER TABLE public.push_registrations
ADD CONSTRAINT push_registrations_mailbox_hash_check
CHECK (
  length(mailbox_hash) = 64 AND
  mailbox_hash ~ '^[a-f0-9]{64}$'  -- Must be 64 lowercase hex characters
);

-- Validate FCM token is not empty and has reasonable length
ALTER TABLE public.push_registrations
DROP CONSTRAINT IF EXISTS push_registrations_fcm_token_check;

ALTER TABLE public.push_registrations
ADD CONSTRAINT push_registrations_fcm_token_check
CHECK (
  length(fcm_token) > 0 AND
  length(fcm_token) <= 500  -- FCM tokens are typically 152-200 chars
);

-- =========================================================================
-- RATE LIMITING (OPTIONAL - FOR ABUSE PREVENTION)
-- =========================================================================
-- Consider adding rate limiting to prevent spam/abuse.
-- This can be done via:
-- 1. PostgreSQL function that checks recent insert count per IP
-- 2. Supabase Edge Functions with Redis/KV storage for rate limiting
-- 3. Application-level rate limiting in client SDK

-- Example: Prevent more than 100 messages per hour from same mailbox_hash
-- CREATE OR REPLACE FUNCTION check_rate_limit()
-- RETURNS TRIGGER AS $$
-- DECLARE
--   recent_count INT;
-- BEGIN
--   SELECT count(*) INTO recent_count
--   FROM public.message_queue
--   WHERE mailbox_hash = NEW.mailbox_hash
--     AND created_at > (now() - INTERVAL '1 hour');
--
--   IF recent_count >= 100 THEN
--     RAISE EXCEPTION 'Rate limit exceeded: max 100 messages per hour per mailbox';
--   END IF;
--
--   RETURN NEW;
-- END;
-- $$ LANGUAGE plpgsql;

-- DROP TRIGGER IF EXISTS rate_limit_on_insert ON public.message_queue;

-- CREATE TRIGGER rate_limit_on_insert
--   BEFORE INSERT ON public.message_queue
--   FOR EACH ROW
--   EXECUTE FUNCTION check_rate_limit();

-- =========================================================================
-- TESTING PHASE 6
-- =========================================================================

-- Test 1: Valid message insert (should succeed)
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch, expires_at)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     'dGVzdCBjaXBoZXJ0ZXh0',
--     floor(extract(epoch from now()))::bigint,
--     now() + INTERVAL '3 days'
-- );

-- Test 2: Invalid mailbox_hash (wrong length) - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES (
--     'abc123',  -- Too short
--     'dGVzdA==',
--     floor(extract(epoch from now()))::bigint
-- );
-- Expected error: "violates check constraint"

-- Test 3: Invalid mailbox_hash (not hex) - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES (
--     'ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ',  -- Contains non-hex chars
--     'dGVzdA==',
--     floor(extract(epoch from now()))::bigint
-- );
-- Expected error: "violates check constraint"

-- Test 4: Invalid epoch (too old) - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     'dGVzdA==',
--     1577836800  -- Jan 1, 2020 - way too old
-- );
-- Expected error: "Epoch ... is too old"

-- Test 5: Invalid epoch (too far in future) - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     'dGVzdA==',
--     2147483647  -- Year 2038 - way too far
-- );
-- Expected error: "Epoch ... is too far in the future"

-- Test 6: Invalid expires_at (too soon) - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch, expires_at)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     'dGVzdA==',
--     floor(extract(epoch from now()))::bigint,
--     now() + INTERVAL '1 hour'  -- Less than 1 day
-- );
-- Expected error: "Expiry ... is too soon"

-- Test 7: Invalid expires_at (too far) - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch, expires_at)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     'dGVzdA==',
--     floor(extract(epoch from now()))::bigint,
--     now() + INTERVAL '30 days'  -- More than 7 days
-- );
-- Expected error: "Expiry ... is too far"

-- Test 8: Empty ciphertext - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     '',  -- Empty string
--     floor(extract(epoch from now()))::bigint
-- );
-- Expected error: "violates check constraint"

-- Test 9: Ciphertext too large (>64KB) - should FAIL
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES (
--     'abc123def456abc123def456abc123def456abc123def456abc123def456abcd',
--     repeat('A', 100000),  -- 100KB of 'A's
--     floor(extract(epoch from now()))::bigint
-- );
-- Expected error: "violates check constraint"

-- Test 10: Invalid FCM token (empty) - should FAIL
-- INSERT INTO public.push_registrations (mailbox_hash, fcm_token)
-- VALUES (
--     'test123test123test123test123test123test123test123test123test123te',
--     ''  -- Empty token
-- );
-- Expected error: "violates check constraint"

-- =========================================================================
-- ERROR RESPONSES DOCUMENTATION
-- =========================================================================
-- When constraints are violated, Postgres returns errors with these codes:
--
-- 23514 - CHECK_VIOLATION
--   - Invalid mailbox_hash format
--   - Invalid ciphertext length
--   - Invalid FCM token
--
-- P0001 - RAISE_EXCEPTION (custom errors from triggers)
--   - Epoch too old/too far in future
--   - Expires_at too soon/too far
--   - Rate limit exceeded (if enabled)
--
-- Client applications should handle these errors and provide user-friendly messages.

-- =========================================================================
-- CLEANUP
-- =========================================================================
-- Remove all test data after validation
-- DELETE FROM public.message_queue WHERE mailbox_hash LIKE 'abc123%';
-- DELETE FROM public.push_registrations WHERE mailbox_hash LIKE 'test123%';

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. All constraints are enforced at the database level for security
-- 2. Triggers run on INSERT to validate time-based constraints
-- 3. Rate limiting is commented out but can be enabled if needed
-- 4. Client SDKs should also validate before sending to reduce server load
-- 5. Consider adding metrics/monitoring for failed constraint violations
