package com.void.slate.network.di

import com.void.slate.network.NetworkClient
import com.void.slate.network.NetworkConfig
import com.void.slate.network.impl.KtorNetworkClient
import com.void.slate.network.impl.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Koin module for network infrastructure.
 *
 * Provides:
 * - NetworkConfig (production/debug/mock modes)
 * - HttpClient (Ktor with OkHttp engine)
 * - RetryPolicy (exponential backoff)
 * - NetworkClient (KtorNetworkClient or MockNetworkClient)
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
                pingInterval = 30_000  // 30 seconds
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
    // Network Client
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
