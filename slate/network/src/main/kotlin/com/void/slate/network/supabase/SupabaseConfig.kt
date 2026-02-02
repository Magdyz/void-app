package com.void.slate.network.supabase

/**
 * Configuration for Supabase client.
 *
 * SECURITY NOTES:
 * - Credentials are loaded from local.properties via BuildConfig
 * - NEVER commit credentials to git
 * - NEVER use service role key in client apps (server-side only)
 * - All data access is protected by Row-Level Security (RLS) policies
 *
 * Setup:
 * 1. Copy local.properties.example to local.properties
 * 2. Add your SUPABASE_URL and SUPABASE_ANON_KEY
 * 3. Get values from: https://supabase.com/dashboard/project/YOUR_PROJECT/settings/api
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
    init {
        require(url.isNotBlank()) {
            "Supabase URL not configured. Add SUPABASE_URL to local.properties"
        }
        require(anonKey.isNotBlank()) {
            "Supabase anon key not configured. Add SUPABASE_ANON_KEY to local.properties"
        }
    }
}
