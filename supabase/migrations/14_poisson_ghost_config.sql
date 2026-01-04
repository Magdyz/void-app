-- =========================================================================
-- PHASE 14: POISSON GHOST PROTOCOL - CONFIGURATION FIX
-- =========================================================================
-- Fixes the permission issue with database settings by using a config table.
-- =========================================================================

-- Create configuration table for heartbeat settings
CREATE TABLE IF NOT EXISTS public.poisson_ghost_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable RLS (even though only functions will access it)
ALTER TABLE public.poisson_ghost_config ENABLE ROW LEVEL SECURITY;

-- Insert default configuration
-- NOTE: Update these values after deployment
INSERT INTO public.poisson_ghost_config (key, value)
VALUES
    ('function_url', 'https://txlamfqcjtqyaqejckke.supabase.co/functions/v1/heartbeat-sender'),
    ('anon_key', 'REPLACE_WITH_YOUR_ANON_KEY')
ON CONFLICT (key) DO UPDATE SET
    value = EXCLUDED.value,
    updated_at = now();

-- =========================================================================
-- FUNCTION: Updated trigger function to use config table
-- =========================================================================
CREATE OR REPLACE FUNCTION trigger_heartbeat_sender()
RETURNS void AS $$
DECLARE
    function_url TEXT;
    anon_key TEXT;
    response http_response;
BEGIN
    -- Get configuration from table instead of database settings
    SELECT value INTO function_url
    FROM public.poisson_ghost_config
    WHERE key = 'function_url';

    SELECT value INTO anon_key
    FROM public.poisson_ghost_config
    WHERE key = 'anon_key';

    -- Skip if not configured
    IF function_url IS NULL OR function_url = '' OR anon_key = 'REPLACE_WITH_YOUR_ANON_KEY' THEN
        RAISE NOTICE 'Heartbeat sender not configured - skipping (update poisson_ghost_config table)';
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- CONFIGURATION INSTRUCTIONS
-- =========================================================================
-- After deployment, update the anon key with this SQL:
--
-- UPDATE public.poisson_ghost_config
-- SET value = '<YOUR_ANON_KEY>', updated_at = now()
-- WHERE key = 'anon_key';
--
-- To verify configuration:
-- SELECT * FROM public.poisson_ghost_config;
--
-- To test manually:
-- SELECT trigger_heartbeat_sender();
