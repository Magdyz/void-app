-- =========================================================================
-- PHASE 10: SCHEDULED CLEANUP JOBS
-- =========================================================================
-- Uses pg_cron extension to run periodic cleanup jobs.
-- =========================================================================

-- Enable pg_cron extension (if not already enabled)
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- =========================================================================
-- SCHEDULED JOBS
-- =========================================================================

-- Schedule: Cleanup expired tokens every 15 minutes
SELECT cron.schedule(
    'cleanup-expired-tokens',
    '*/15 * * * *',  -- Every 15 minutes
    $$SELECT cleanup_expired_tokens()$$
);

-- Schedule: Cleanup rate limit tracker every hour
SELECT cron.schedule(
    'cleanup-rate-limit-tracker',
    '0 * * * *',  -- Every hour at :00
    $$SELECT cleanup_rate_limit_tracker()$$
);

-- Schedule: Cleanup expired messages (existing TTL function from migration 03)
-- Only schedule if not already scheduled
DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM cron.job WHERE jobname = 'cleanup-expired-records'
    ) THEN
        PERFORM cron.schedule(
            'cleanup-expired-records',
            '0 */6 * * *',  -- Every 6 hours
            $$SELECT public.cleanup_expired_records()$$
        );
    END IF;
END $migration$;

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. pg_cron runs in UTC timezone
-- 2. View scheduled jobs: SELECT * FROM cron.job;
-- 3. View job run history: SELECT * FROM cron.job_run_details ORDER BY start_time DESC LIMIT 20;
-- 4. Unschedule job: SELECT cron.unschedule('job-name');
-- 5. Jobs run as the postgres user with SECURITY DEFINER functions
