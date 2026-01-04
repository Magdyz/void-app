-- =========================================================================
-- PHASE 8: SECURE RLS POLICIES WITH TOKEN VALIDATION
-- =========================================================================
-- ⚠️  WARNING: THIS IS A BREAKING CHANGE!
-- ⚠️  Deploy this ONLY after clients have been updated with token support
-- ⚠️  Old clients will be unable to fetch messages after this migration
-- =========================================================================
-- Replaces the insecure `USING (true)` policies with token-based auth.
-- Clients must provide valid ephemeral tokens via X-Mailbox-Token header.
-- =========================================================================

-- Drop old insecure policies
DROP POLICY IF EXISTS "Select messages by mailbox_hash" ON public.message_queue;
DROP POLICY IF EXISTS "Delete messages by mailbox_hash" ON public.message_queue;
DROP POLICY IF EXISTS "Anyone can insert messages" ON public.message_queue;
DROP POLICY IF EXISTS "Select push registrations" ON public.push_registrations;
DROP POLICY IF EXISTS "Update push registrations" ON public.push_registrations;
DROP POLICY IF EXISTS "Delete push registrations" ON public.push_registrations;
DROP POLICY IF EXISTS "Anyone can register push tokens" ON public.push_registrations;

-- =========================================================================
-- HELPER FUNCTION: Extract Token from Request Header
-- =========================================================================

CREATE OR REPLACE FUNCTION get_current_token_id()
RETURNS UUID AS $$
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
$$ LANGUAGE plpgsql STABLE;

-- =========================================================================
-- MESSAGE_QUEUE: Token-based RLS
-- =========================================================================

-- SELECT policy: Requires valid token
CREATE POLICY "Select messages with valid token"
ON public.message_queue
FOR SELECT
TO anon, authenticated
USING (
    -- Validate token matches mailbox_hash
    validate_query_token(get_current_token_id(), mailbox_hash)
);

-- DELETE policy: Requires valid token
CREATE POLICY "Delete messages with valid token"
ON public.message_queue
FOR DELETE
TO anon, authenticated
USING (
    -- Validate token matches mailbox_hash
    validate_query_token(get_current_token_id(), mailbox_hash)
);

-- INSERT policy: No token required (senders don't need auth)
-- Keep existing policy from migration 05
CREATE POLICY "Anyone can insert messages"
ON public.message_queue
FOR INSERT
TO anon, authenticated
WITH CHECK (true);

-- =========================================================================
-- PUSH_REGISTRATIONS: Token-based RLS
-- =========================================================================

-- SELECT policy: Requires valid token
CREATE POLICY "Select push registration with valid token"
ON public.push_registrations
FOR SELECT
TO anon, authenticated
USING (
    validate_query_token(get_current_token_id(), mailbox_hash)
);

-- UPDATE policy: Requires valid token
CREATE POLICY "Update push registration with valid token"
ON public.push_registrations
FOR UPDATE
TO anon, authenticated
USING (
    validate_query_token(get_current_token_id(), mailbox_hash)
);

-- DELETE policy: Requires valid token
CREATE POLICY "Delete push registration with valid token"
ON public.push_registrations
FOR DELETE
TO anon, authenticated
USING (
    validate_query_token(get_current_token_id(), mailbox_hash)
);

-- INSERT policy: No token required (first-time registration)
CREATE POLICY "Anyone can register push tokens"
ON public.push_registrations
FOR INSERT
TO anon, authenticated
WITH CHECK (true);

-- =========================================================================
-- ROLLBACK INSTRUCTIONS
-- =========================================================================
-- If this migration breaks clients, run the following to revert:
--
-- DROP POLICY IF EXISTS "Select messages with valid token" ON public.message_queue;
-- DROP POLICY IF EXISTS "Delete messages with valid token" ON public.message_queue;
-- DROP POLICY IF EXISTS "Select push registration with valid token" ON public.push_registrations;
-- DROP POLICY IF EXISTS "Update push registration with valid token" ON public.push_registrations;
-- DROP POLICY IF EXISTS "Delete push registration with valid token" ON public.push_registrations;
--
-- CREATE POLICY "Select messages by mailbox_hash" ON public.message_queue
-- FOR SELECT TO anon, authenticated USING (true);
--
-- CREATE POLICY "Delete messages by mailbox_hash" ON public.message_queue
-- FOR DELETE TO anon, authenticated USING (true);
--
-- CREATE POLICY "Select push registrations" ON public.push_registrations
-- FOR SELECT TO anon, authenticated USING (true);
--
-- CREATE POLICY "Update push registrations" ON public.push_registrations
-- FOR UPDATE TO anon, authenticated USING (true);
--
-- CREATE POLICY "Delete push registrations" ON public.push_registrations
-- FOR DELETE TO anon, authenticated USING (true);
-- =========================================================================

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. All SELECT/DELETE/UPDATE operations require valid ephemeral token
-- 2. INSERT operations remain open (message sending, push registration)
-- 3. Tokens are passed via X-Mailbox-Token custom header
-- 4. Tokens are validated against mailbox_hash automatically by RLS
-- 5. validate_query_token() marks tokens as used (single-use)
-- 6. Old clients WITHOUT token support will get RLS policy violations
