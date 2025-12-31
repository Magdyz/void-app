package com.void.slate.network.supabase

/**
 * Configuration for Supabase client.
 *
 * SECURITY NOTES:
 * - NEVER commit the anon key to git (use BuildConfig or secure config)
 * - NEVER use service role key in client apps (server-side only)
 * - All data access is protected by Row-Level Security (RLS) policies
 */
data class SupabaseConfig(
    /**
     * Supabase project URL.
     * Example: "https://[project-ref].supabase.co"
     */
    val url: String,

    /**
     * Supabase anonymous key (safe for client use).
     * This is the PUBLIC key - RLS policies protect data access.
     */
    val anonKey: String,

    /**
     * Enable debug logging for Supabase client.
     */
    val enableLogging: Boolean = false
) {
    companion object {
        /**
         * Local development configuration.
         * Points to local Supabase instance running via Docker.
         */
        val LOCAL = SupabaseConfig(
            url = "http://localhost:54321",
            anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InR4bGFtZnFjanRxeWFxZWpja2tlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjcxMzIxMDYsImV4cCI6MjA4MjcwODEwNn0.hPRzmLf7hPb-qUAPrV-hJfgj7pvivyzxr6MC8ZzWvug",
            enableLogging = true
        )

        /**
         * Production configuration.
         * Connected to live Supabase project: txlamfqcjtqyaqejckke
         *
         * IMPORTANT: Get your anon key from:
         * https://supabase.com/dashboard/project/txlamfqcjtqyaqejckke/settings/api
         */
        val PRODUCTION = SupabaseConfig(
            url = "https://txlamfqcjtqyaqejckke.supabase.co",
            anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InR4bGFtZnFjanRxeWFxZWpja2tlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjcxMzIxMDYsImV4cCI6MjA4MjcwODEwNn0.hPRzmLf7hPb-qUAPrV-hJfgj7pvivyzxr6MC8ZzWvug", // TODO: Replace with actual anon key from dashboard
            enableLogging = false
        )

        /**
         * Debug/staging configuration (uses production server with logging).
         */
        val DEBUG = SupabaseConfig(
            url = "https://txlamfqcjtqyaqejckke.supabase.co",
            anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InR4bGFtZnFjanRxeWFxZWpja2tlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjcxMzIxMDYsImV4cCI6MjA4MjcwODEwNn0.hPRzmLf7hPb-qUAPrV-hJfgj7pvivyzxr6MC8ZzWvug", // TODO: Replace with actual anon key from dashboard
            enableLogging = true
        )
    }
}
