-- Run this in Supabase SQL Editor to check table visibility

-- 1. Check if tables exist and which schema they're in
SELECT schemaname, tablename
FROM pg_tables
WHERE tablename IN ('message_queue', 'push_registrations')
ORDER BY schemaname, tablename;

-- 2. Check table ownership
SELECT
    schemaname,
    tablename,
    tableowner
FROM pg_tables
WHERE tablename IN ('message_queue', 'push_registrations');

-- 3. Check if tables are in public schema specifically
SELECT table_name, table_schema
FROM information_schema.tables
WHERE table_name IN ('message_queue', 'push_registrations');

-- 4. Verify RLS is enabled
SELECT
    schemaname,
    tablename,
    rowsecurity as rls_enabled
FROM pg_tables
WHERE tablename IN ('message_queue', 'push_registrations');
