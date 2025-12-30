package com.void.slate.network

/**
 * Network configuration for VOID server connectivity.
 *
 * Supports both production servers and local mock endpoints.
 */
data class NetworkConfig(
    val serverUrl: String = "https://void-relay.example.com",
    val apiVersion: String = "v1",
    val connectionTimeout: Long = 30_000,
    val retryAttempts: Int = 3,
    val retryBackoffMs: Long = 1000,
    val enableWebSockets: Boolean = true,
    val mockMode: Boolean = false
) {
    companion object {
        /**
         * Default production configuration.
         * Points to a production relay server (not yet implemented).
         */
        val DEFAULT = NetworkConfig()

        /**
         * Local mock configuration for development.
         * Enables MockNetworkClient for serverless testing.
         */
        val LOCAL_MOCK = NetworkConfig(
            serverUrl = "http://localhost:8080",
            mockMode = true
        )

        /**
         * Debug configuration for testing with local server.
         * Using local IP: works for physical devices.
         * For emulator, use: adb -e forward tcp:8080 tcp:8080
         */
        val DEBUG = NetworkConfig(
            serverUrl = "http://192.168.1.26:8080", // Your local IP
            enableWebSockets = true,
            mockMode = false
        )
    }

    /**
     * Full API base URL.
     */
    val apiBaseUrl: String
        get() = "$serverUrl/api/$apiVersion"

    /**
     * WebSocket URL for real-time messaging.
     */
    val webSocketUrl: String
        get() = serverUrl.replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws"
}
