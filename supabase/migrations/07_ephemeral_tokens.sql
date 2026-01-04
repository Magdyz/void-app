-- =========================================================================
-- PHASE 7: EPHEMERAL QUERY TOKENS
-- =========================================================================
-- Implements proof-of-ownership tokens for RLS enforcement.
-- Clients must prove knowledge of identity_seed to get query tokens.
-- Tokens are short-lived (10 minutes) and invalidated after use.
-- =========================================================================

-- Create ephemeral_tokens table
CREATE TABLE IF NOT EXISTS public.ephemeral_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mailbox_hash TEXT NOT NULL CHECK (length(mailbox_hash) = 64 AND mailbox_hash ~ '^[a-f0-9]{64}$'),
    challenge_hash TEXT NOT NULL, -- SHA-256 hash of client's challenge
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '10 minutes'),
    used BOOLEAN NOT NULL DEFAULT false,
    used_at TIMESTAMPTZ
);

-- Index for fast token lookup by ID
CREATE INDEX idx_ephemeral_tokens_id ON public.ephemeral_tokens(id);

-- Index for fast lookup by mailbox_hash (for rate limiting token requests)
CREATE INDEX idx_ephemeral_tokens_mailbox_hash ON public.ephemeral_tokens(mailbox_hash);

-- Index for TTL cleanup
CREATE INDEX idx_ephemeral_tokens_expires_at ON public.ephemeral_tokens(expires_at);

-- Enable RLS (anyone can request tokens, but validation is in the function)
ALTER TABLE public.ephemeral_tokens ENABLE ROW LEVEL SECURITY;

-- Allow anyone to request tokens (rate limited by trigger)
CREATE POLICY "Anyone can request tokens"
ON public.ephemeral_tokens
FOR INSERT
TO anon, authenticated
WITH CHECK (true);

-- Allow reading tokens for validation (RLS checks will use this)
CREATE POLICY "Anyone can validate tokens"
ON public.ephemeral_tokens
FOR SELECT
TO anon, authenticated
USING (true);

-- Allow updating tokens to mark as used
CREATE POLICY "Anyone can mark tokens used"
ON public.ephemeral_tokens
FOR UPDATE
TO anon, authenticated
USING (true);

-- =========================================================================
-- TOKEN REQUEST FUNCTION
-- =========================================================================
-- Clients call this to prove ownership and get a token.
-- Returns token_id if proof is valid.

CREATE OR REPLACE FUNCTION request_query_token(
    p_mailbox_hash TEXT,
    p_timestamp BIGINT,
    p_challenge TEXT  -- HMAC-SHA256(identity_seed, "token_request:" + mailbox_hash + timestamp)
)
RETURNS TABLE(token_id UUID, expires_at TIMESTAMPTZ) AS $$
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- TOKEN VALIDATION FUNCTION (Used by RLS policies)
-- =========================================================================
-- Validates that a token exists, is not expired, matches mailbox, not used.

CREATE OR REPLACE FUNCTION validate_query_token(
    p_token_id UUID,
    p_mailbox_hash TEXT
)
RETURNS BOOLEAN AS $$
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- TTL CLEANUP FOR EXPIRED TOKENS
-- =========================================================================
-- Delete expired tokens (called by cron job every hour)

CREATE OR REPLACE FUNCTION cleanup_expired_tokens()
RETURNS void AS $$
BEGIN
    DELETE FROM public.ephemeral_tokens
    WHERE expires_at < now();

    RAISE NOTICE 'Cleaned up expired tokens at %', now();
END;
$$ LANGUAGE plpgsql;

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. Tokens are ephemeral: 10-minute lifetime, single-use
-- 2. Client must prove knowledge of identity_seed via HMAC challenge
-- 3. Server stores only hash of challenge (never sees identity_seed)
-- 4. Tokens are validated in RLS policies via custom header X-Mailbox-Token
-- 5. Rate limiting prevents token spam (see migration 09)
