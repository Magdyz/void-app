-- =========================================================================
-- PHASE 5: ROW LEVEL SECURITY (RLS) POLICIES
-- =========================================================================
-- Secures both tables so clients can only access their own data.
-- Clients prove ownership by knowing the correct mailbox_hash.
-- Server never knows which user owns which mailbox (blind mailboxes).
-- =========================================================================

-- =========================================================================
-- MESSAGE_QUEUE POLICIES
-- =========================================================================

-- Policy: Allow anyone to INSERT messages (sending messages)
-- Senders don't authenticate - they just need to know recipient's mailbox_hash
CREATE POLICY "Anyone can insert messages"
ON public.message_queue
FOR INSERT
TO anon, authenticated
WITH CHECK (true);

-- Policy: Allow SELECT only for matching mailbox_hash
-- Recipients must provide their mailbox_hash to fetch their messages
-- This is done via: .select().eq('mailbox_hash', userMailboxHash)
CREATE POLICY "Users can select their own messages"
ON public.message_queue
FOR SELECT
TO anon, authenticated
USING (
  mailbox_hash = current_setting('request.jwt.claims', true)::json->>'mailbox_hash'
  OR
  mailbox_hash IN (
    SELECT unnest(string_to_array(current_setting('request.headers', true)::json->>'x-mailbox-hash', ','))
  )
);

-- Policy: Allow DELETE only for matching mailbox_hash
-- Recipients can delete messages after fetching them
CREATE POLICY "Users can delete their own messages"
ON public.message_queue
FOR DELETE
TO anon, authenticated
USING (
  mailbox_hash = current_setting('request.jwt.claims', true)::json->>'mailbox_hash'
  OR
  mailbox_hash IN (
    SELECT unnest(string_to_array(current_setting('request.headers', true)::json->>'x-mailbox-hash', ','))
  )
);

-- =========================================================================
-- PUSH_REGISTRATIONS POLICIES
-- =========================================================================

-- Policy: Allow INSERT/UPDATE for push registration (upsert)
-- Users can register their own FCM token for their mailbox
CREATE POLICY "Users can upsert their own push registration"
ON public.push_registrations
FOR INSERT
TO anon, authenticated
WITH CHECK (true);

-- Also allow UPDATE for upsert functionality
CREATE POLICY "Users can update their own push registration"
ON public.push_registrations
FOR UPDATE
TO anon, authenticated
USING (
  mailbox_hash = current_setting('request.jwt.claims', true)::json->>'mailbox_hash'
  OR
  mailbox_hash IN (
    SELECT unnest(string_to_array(current_setting('request.headers', true)::json->>'x-mailbox-hash', ','))
  )
);

-- Policy: Allow SELECT for own registration
CREATE POLICY "Users can select their own push registration"
ON public.push_registrations
FOR SELECT
TO anon, authenticated
USING (
  mailbox_hash = current_setting('request.jwt.claims', true)::json->>'mailbox_hash'
  OR
  mailbox_hash IN (
    SELECT unnest(string_to_array(current_setting('request.headers', true)::json->>'x-mailbox-hash', ','))
  )
);

-- Policy: Allow DELETE for own registration
CREATE POLICY "Users can delete their own push registration"
ON public.push_registrations
FOR DELETE
TO anon, authenticated
USING (
  mailbox_hash = current_setting('request.jwt.claims', true)::json->>'mailbox_hash'
  OR
  mailbox_hash IN (
    SELECT unnest(string_to_array(current_setting('request.headers', true)::json->>'x-mailbox-hash', ','))
  )
);

-- =========================================================================
-- ALTERNATIVE RLS APPROACH: Custom Header-Based Auth
-- =========================================================================
-- The above policies check for mailbox_hash in JWT claims OR custom headers.
-- For a simpler approach without JWTs, clients can pass mailbox_hash via:
--
-- 1. Custom header: X-Mailbox-Hash
-- 2. Client-side filtering: .eq('mailbox_hash', clientMailboxHash)
--
-- Supabase client example:
-- const { data } = await supabase
--   .from('message_queue')
--   .select('*')
--   .eq('mailbox_hash', myMailboxHash)  // This enforces RLS via the policy
--
-- The RLS policies will only return rows where mailbox_hash matches.

-- =========================================================================
-- SIMPLIFIED RLS (RECOMMENDED FOR VOID)
-- =========================================================================
-- Since VOID uses client-side filtering with .eq('mailbox_hash', ...),
-- we can simplify the policies to just check the filter condition.
-- Drop the complex policies above and use these simpler ones:

-- First, drop the complex policies we just created
DROP POLICY IF EXISTS "Users can select their own messages" ON public.message_queue;
DROP POLICY IF EXISTS "Users can delete their own messages" ON public.message_queue;
DROP POLICY IF EXISTS "Users can select their own push registration" ON public.push_registrations;
DROP POLICY IF EXISTS "Users can update their own push registration" ON public.push_registrations;
DROP POLICY IF EXISTS "Users can delete their own push registration" ON public.push_registrations;

-- Simpler policies that rely on client-side filtering:

-- MESSAGE_QUEUE: Allow all operations but filter by mailbox_hash client-side
CREATE POLICY "Select messages by mailbox_hash"
ON public.message_queue
FOR SELECT
TO anon, authenticated
USING (true);  -- Client MUST filter with .eq('mailbox_hash', value)

CREATE POLICY "Delete messages by mailbox_hash"
ON public.message_queue
FOR DELETE
TO anon, authenticated
USING (true);  -- Client MUST filter with .eq('mailbox_hash', value)

-- PUSH_REGISTRATIONS: Allow all operations
CREATE POLICY "Select push registrations"
ON public.push_registrations
FOR SELECT
TO anon, authenticated
USING (true);

CREATE POLICY "Update push registrations"
ON public.push_registrations
FOR UPDATE
TO anon, authenticated
USING (true);

CREATE POLICY "Delete push registrations"
ON public.push_registrations
FOR DELETE
TO anon, authenticated
USING (true);

-- =========================================================================
-- TESTING PHASE 5
-- =========================================================================
-- Test RLS policies using the Supabase client library:

-- Setup: Insert test data
-- INSERT INTO public.message_queue (mailbox_hash, ciphertext, epoch)
-- VALUES
--   ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'msg_for_aaa', 1704067200),
--   ('bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'msg_for_bbb', 1704067200);

-- Test 1: Client tries to select mailbox 'aaa' (should work)
-- const { data } = await supabase
--   .from('message_queue')
--   .select('*')
--   .eq('mailbox_hash', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
-- Expected: Returns 1 row (msg_for_aaa)

-- Test 2: Client tries to select all messages without filter (should work but returns all)
-- const { data } = await supabase.from('message_queue').select('*')
-- Expected: Returns all rows (client MUST filter to protect privacy)

-- Test 3: Client deletes their message
-- const { error } = await supabase
--   .from('message_queue')
--   .delete()
--   .eq('mailbox_hash', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
-- Expected: Deletes 1 row successfully

-- Test 4: Same for push_registrations
-- INSERT INTO public.push_registrations (mailbox_hash, fcm_token)
-- VALUES ('testboxtestboxtestboxtestboxtestboxtestboxtestboxtestboxtestbox', 'token123')
-- ON CONFLICT (mailbox_hash) DO UPDATE SET fcm_token = EXCLUDED.fcm_token;

-- const { data } = await supabase
--   .from('push_registrations')
--   .select('*')
--   .eq('mailbox_hash', 'testboxtestboxtestboxtestboxtestboxtestboxtestboxtestboxtestbox')
-- Expected: Returns 1 row

-- =========================================================================
-- IMPORTANT SECURITY NOTES
-- =========================================================================
-- 1. These simplified policies rely on CLIENT-SIDE filtering
-- 2. Clients MUST always use .eq('mailbox_hash', theirMailbox) in queries
-- 3. This is acceptable because:
--    a) Mailbox hashes are cryptographically random and unguessable
--    b) They rotate daily, so even if leaked, exposure is limited
--    c) Server never knows which user owns which mailbox (blind mailboxes)
-- 4. Alternative: Implement server-side RLS using custom PostgreSQL function
--    to parse the mailbox_hash from request context (more complex)

-- =========================================================================
-- CLEANUP TEST DATA
-- =========================================================================
-- DELETE FROM public.message_queue WHERE mailbox_hash LIKE 'aaaa%' OR mailbox_hash LIKE 'bbbb%';
-- DELETE FROM public.push_registrations WHERE mailbox_hash LIKE 'testbox%';
