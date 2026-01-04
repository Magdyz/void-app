-- =========================================================================
-- PHASE 13: POISSON GHOST PROTOCOL - HEARTBEAT SCHEDULER
-- =========================================================================
-- Sets up pg_cron job to call heartbeat-sender Edge Function every minute.
--
-- Architecture:
-- - pg_cron runs every minute
-- - Calls heartbeat-sender Edge Function via HTTP
-- - Edge Function queries users due for heartbeat
-- - Sends batch FCM pushes
-- - Updates next heartbeat time with random interval
--
-- Privacy Benefits:
-- - Creates constant background traffic
-- - Random intervals per user (Poisson distribution)
-- - Indistinguishable from real message notifications
-- - ISP/Google cannot correlate activity patterns
-- =========================================================================

-- Enable http extension for making HTTP requests from database
CREATE EXTENSION IF NOT EXISTS http;

-- =========================================================================
-- FUNCTION: Call heartbeat-sender Edge Function
-- =========================================================================
-- This function is called by pg_cron every minute.
-- It invokes the heartbeat-sender Edge Function via HTTP POST.
CREATE OR REPLACE FUNCTION trigger_heartbeat_sender()
RETURNS void AS $$
DECLARE
    function_url TEXT;
    anon_key TEXT;
    response http_response;
BEGIN
    -- Get Supabase function URL from environment
    -- Format: https://<project-ref>.supabase.co/functions/v1/heartbeat-sender
    -- NOTE: This needs to be configured per deployment
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =========================================================================
-- SCHEDULE: Heartbeat sender (every 1 minute)
-- =========================================================================
-- Runs trigger_heartbeat_sender() every minute.
-- Only schedule if not already scheduled.
DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM cron.job WHERE jobname = 'poisson-ghost-heartbeat'
    ) THEN
        PERFORM cron.schedule(
            'poisson-ghost-heartbeat',
            '* * * * *',  -- Every minute
            $$SELECT trigger_heartbeat_sender()$$
        );
        RAISE NOTICE 'Poisson Ghost heartbeat scheduler created';
    ELSE
        RAISE NOTICE 'Poisson Ghost heartbeat scheduler already exists';
    END IF;
END $migration$;

-- =========================================================================
-- CONFIGURATION INSTRUCTIONS
-- =========================================================================
-- After deployment, set these configuration variables in Supabase:
--
-- 1. Get your Supabase project URL and anon key from dashboard
--
-- 2. Run these SQL commands in Supabase SQL Editor:
--
--    ALTER DATABASE postgres SET app.supabase_function_url = 'https://<your-project-ref>.supabase.co/functions/v1/heartbeat-sender';
--    ALTER DATABASE postgres SET app.supabase_anon_key = '<your-anon-key>';
--
-- 3. Verify configuration:
--
--    SELECT current_setting('app.supabase_function_url', true);
--    SELECT current_setting('app.supabase_anon_key', true);
--
-- 4. Test manually:
--
--    SELECT trigger_heartbeat_sender();
--
-- 5. View scheduled jobs:
--
--    SELECT * FROM cron.job WHERE jobname = 'poisson-ghost-heartbeat';
--
-- 6. View job history:
--
--    SELECT * FROM cron.job_run_details
--    WHERE jobid = (SELECT jobid FROM cron.job WHERE jobname = 'poisson-ghost-heartbeat')
--    ORDER BY start_time DESC LIMIT 10;
--
-- 7. Disable heartbeat (if needed):
--
--    SELECT cron.unschedule('poisson-ghost-heartbeat');
--
-- 8. Re-enable heartbeat (if needed):
--
--    SELECT cron.schedule('poisson-ghost-heartbeat', '* * * * *', $$SELECT trigger_heartbeat_sender()$$);

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. Heartbeat sender runs every minute and processes up to 100 users per run
-- 2. Edge Function handles batching and FCM sending
-- 3. This scheduler is backward compatible - existing message notifications still work
-- 4. Users without heartbeat configured (old registrations) continue to work normally
-- 5. Configuration is deployment-specific (dev/staging/prod)
-- 6. Uses SECURITY DEFINER to allow cron user to access settings
