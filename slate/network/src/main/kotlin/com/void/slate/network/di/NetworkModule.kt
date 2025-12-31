package com.void.slate.network.di

import android.content.Context
import com.void.slate.network.NetworkClient
import com.void.slate.network.NetworkConfig
import com.void.slate.network.impl.KtorNetworkClient
import com.void.slate.network.impl.RetryPolicy
import com.void.slate.network.mailbox.MailboxDerivation
import com.void.slate.network.push.PushRegistration
import com.void.slate.network.supabase.MessageFetcher
import com.void.slate.network.supabase.MessageSender
import com.void.slate.network.supabase.SupabaseConfig
import com.void.slate.network.sync.SyncScheduler
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Koin module for network infrastructure.
 *
 * Provides:
 * - NetworkConfig (production/debug/mock modes)
 * - SupabaseConfig and SupabaseClient
 * - HttpClient (Ktor with OkHttp engine)
 * - RetryPolicy (exponential backoff)
 * - NetworkClient (KtorNetworkClient or MockNetworkClient)
 * - Mailbox derivation and message send/fetch components
 * - Push notification registration
 * - Sync scheduling
 */
val networkModule = module {

    // ═══════════════════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════════════════

    single {
        // TODO: Use BuildConfig.DEBUG when available
        // For now, use DEBUG mode to connect to local server
        // Change to LOCAL_MOCK for offline testing
        NetworkConfig.DEBUG
    }

    single {
        // Use DEBUG for development (production server with logging)
        // Change to PRODUCTION for release builds
        SupabaseConfig.DEBUG
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP Client (Ktor)
    // ═══════════════════════════════════════════════════════════════════

    single {
        HttpClient(OkHttp) {
            // JSON serialization
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    prettyPrint = false
                })
            }

            // WebSocket support
            install(WebSockets) {
                pingIntervalMillis = 30_000  // 30 seconds
            }

            // Default request configuration
            defaultRequest {
                val config = get<NetworkConfig>()
                url(config.serverUrl)
            }

            // Engine configuration
            engine {
                val networkConfig = get<NetworkConfig>()
                preconfigured = OkHttpClient.Builder()
                    .connectTimeout(networkConfig.connectionTimeout, TimeUnit.MILLISECONDS)
                    .readTimeout(networkConfig.connectionTimeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(networkConfig.connectionTimeout, TimeUnit.MILLISECONDS)
                    .build()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Retry Policy
    // ═══════════════════════════════════════════════════════════════════

    single {
        val config = get<NetworkConfig>()
        RetryPolicy(
            maxAttempts = config.retryAttempts,
            baseBackoffMs = config.retryBackoffMs
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // Supabase Client
    // ═══════════════════════════════════════════════════════════════════

    single<SupabaseClient> {
        val supabaseConfig = get<SupabaseConfig>()

        createSupabaseClient(
            supabaseUrl = supabaseConfig.url,
            supabaseKey = supabaseConfig.anonKey
        ) {
            install(Postgrest)
            install(Realtime)

            // Note: Logging configuration for Supabase client
            // Can be enabled via supabaseConfig.enableLogging if needed
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Mailbox Derivation
    // ═══════════════════════════════════════════════════════════════════

    single {
        MailboxDerivation(
            crypto = get()  // Injected from slate:core
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // Message Send/Fetch
    // ═══════════════════════════════════════════════════════════════════

    single {
        MessageFetcher(
            supabase = get()
        )
    }

    single {
        MessageSender(
            supabase = get(),
            mailboxDerivation = get()
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // Push Notifications
    // ═══════════════════════════════════════════════════════════════════

    single {
        PushRegistration(
            supabase = get(),
            mailboxDerivation = get()
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // Sync Scheduling
    // ═══════════════════════════════════════════════════════════════════

    single {
        SyncScheduler(
            context = get<Context>(),
            pushRegistration = get()
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // Network Client (Legacy Ktor-based)
    // ═══════════════════════════════════════════════════════════════════

    single<NetworkClient> {
        val config = get<NetworkConfig>()

        if (config.mockMode) {
            // Use mock implementation for local testing
            com.void.slate.network.mock.MockNetworkClient(
                simulatedLatencyMs = 200L,
                errorRate = 0f  // No errors by default
            )
        } else {
            // Use real Ktor implementation
            KtorNetworkClient(
                httpClient = get(),
                config = config,
                retryPolicy = get(),
                accountIdProvider = get()  // Injected from app layer
            )
        }
    }
}
