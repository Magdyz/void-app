-- =========================================================================
-- PHASE 11: FIX MESSAGE TTL VALIDATION (CLOCK SKEW TOLERANCE)
-- =========================================================================
-- Fixes the strict 7-day expiry validation that fails due to clock skew.
-- Adds 1-hour grace period to account for device/server time differences.
-- =========================================================================

-- Drop existing function
DROP FUNCTION IF EXISTS validate_message_expiry() CASCADE;

-- Recreate with more lenient validation
CREATE OR REPLACE FUNCTION validate_message_expiry()
RETURNS TRIGGER AS $$
BEGIN
  -- Minimum: Must be at least 1 day from now
  IF NEW.expires_at < (now() + INTERVAL '1 day') THEN
    RAISE EXCEPTION 'Expiry % is too soon (must be at least 1 day from now)', NEW.expires_at;
  END IF;

  -- Maximum: Allow up to 7 days + 1 hour to account for clock skew
  -- This gives a reasonable margin while preventing abuse
  IF NEW.expires_at > (now() + INTERVAL '7 days 1 hour') THEN
    RAISE EXCEPTION 'Expiry % is too far (must be within 7 days from now)', NEW.expires_at;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Recreate trigger
CREATE TRIGGER enforce_message_expiry
  BEFORE INSERT OR UPDATE ON public.message_queue
  FOR EACH ROW
  EXECUTE FUNCTION validate_message_expiry();

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. Added 1-hour grace period to account for:
--    - Device clock drift (phones/tablets may be slightly ahead)
--    - Network latency (time between client calculating and server receiving)
--    - Server processing time (time between receiving and validating)
--
-- 2. This still prevents abuse (messages can't be set to expire years away)
--    while allowing legitimate messages to be accepted
--
-- 3. The 1-hour margin is standard practice for distributed systems
-- =========================================================================
