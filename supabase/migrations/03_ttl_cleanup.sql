-- =========================================================================
-- PHASE 3: TTL CLEANUP
-- =========================================================================
-- Scheduled job to delete expired records from both tables every hour.
-- Uses pg_cron extension for PostgreSQL-native scheduling.
-- =========================================================================

-- Enable pg_cron extension (run as postgres superuser or via Supabase dashboard)
-- Note: On Supabase, pg_cron is available on paid plans.
-- For free tier, use Supabase Edge Functions with external cron (see alternative below).
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Create the cleanup function
CREATE OR REPLACE FUNCTION public.cleanup_expired_records()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
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

-- Schedule the cleanup function to run every hour using pg_cron
-- This runs at minute 0 of every hour (e.g., 1:00, 2:00, 3:00, etc.)
SELECT cron.schedule(
    'cleanup-expired-records',     -- Job name
    '0 * * * *',                   -- Cron expression: every hour at minute 0
    'SELECT public.cleanup_expired_records();'
);

-- =========================================================================
-- ALTERNATIVE: Edge Function + External Cron (for Supabase Free Tier)
-- =========================================================================
-- If pg_cron is not available, create this Edge Function and trigger it
-- with an external cron service (e.g., GitHub Actions, Vercel Cron, etc.)
--
-- Edge Function (TypeScript):
-- ```typescript
-- import { createClient } from '@supabase/supabase-js'
--
-- Deno.serve(async (req) => {
--   // Verify authorization (use a secret token)
--   const authHeader = req.headers.get('authorization')
--   if (authHeader !== `Bearer ${Deno.env.get('CLEANUP_SECRET')}`) {
--     return new Response('Unauthorized', { status: 401 })
--   }
--
--   const supabase = createClient(
--     Deno.env.get('SUPABASE_URL')!,
--     Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
--   )
--
--   // Delete expired messages
--   const { error: msgError } = await supabase
--     .from('message_queue')
--     .delete()
--     .lt('expires_at', new Date().toISOString())
--
--   // Delete expired push registrations
--   const { error: pushError } = await supabase
--     .from('push_registrations')
--     .delete()
--     .lt('expires_at', new Date().toISOString())
--
--   if (msgError || pushError) {
--     return new Response(JSON.stringify({ error: msgError || pushError }), {
--       status: 500,
--       headers: { 'Content-Type': 'application/json' }
--     })
--   }
--
--   return new Response(JSON.stringify({ success: true }), {
--     headers: { 'Content-Type': 'application/json' }
--   })
-- })
-- ```
--
-- Then schedule with GitHub Actions (.github/workflows/cleanup-cron.yml):
-- ```yaml
-- name: Supabase Cleanup
-- on:
--   schedule:
--     - cron: '0 * * * *'  # Every hour
-- jobs:
--   cleanup:
--     runs-on: ubuntu-latest
--     steps:
--       - name: Trigger cleanup
--         run: |
--           curl -X POST \
--             -H "Authorization: Bearer ${{ secrets.CLEANUP_SECRET }}" \
--             https://your-project.supabase.co/functions/v1/cleanup-expired
-- ```

-- =========================================================================
-- TESTING PHASE 3
-- =========================================================================
-- Run these queries in Supabase SQL Editor to verify the cleanup works:

-- Test 1: Insert expired message
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch, expires_at)
-- VALUES (
--     'expired1expired1expired1expired1expired1expired1expired1expired1ex',
--     'expired_message',
--     1704067200,
--     now() - INTERVAL '1 hour'
-- );

-- Test 2: Insert expired push registration
-- INSERT INTO public.push_registrations (mailbox_hash, fcm_token, expires_at)
-- VALUES (
--     'expired2expired2expired2expired2expired2expired2expired2expired2ex',
--     'expired_token',
--     now() - INTERVAL '1 hour'
-- );

-- Test 3: Run cleanup manually
-- SELECT public.cleanup_expired_records();

-- Test 4: Verify expired rows are gone
-- SELECT count(*) FROM public.message_queue
-- WHERE mailbox_hash = 'expired1expired1expired1expired1expired1expired1expired1expired1ex';
-- -- Should return 0

-- SELECT count(*) FROM public.push_registrations
-- WHERE mailbox_hash = 'expired2expired2expired2expired2expired2expired2expired2expired2ex';
-- -- Should return 0

-- =========================================================================
-- MONITORING
-- =========================================================================
-- To check if the cron job is running (pg_cron only):
-- SELECT * FROM cron.job WHERE jobname = 'cleanup-expired-records';
--
-- To view cron job run history:
-- SELECT * FROM cron.job_run_details
-- WHERE jobid = (SELECT jobid FROM cron.job WHERE jobname = 'cleanup-expired-records')
-- ORDER BY start_time DESC
-- LIMIT 10;

-- =========================================================================
-- NOTES
-- =========================================================================
-- 1. pg_cron requires PostgreSQL superuser or Supabase paid plan
-- 2. For free tier, use Edge Function + external cron trigger
-- 3. Cleanup runs every hour to minimize storage usage
-- 4. SECURITY DEFINER allows function to delete rows despite RLS
-- 5. Consider adding monitoring/alerting for cleanup failures
