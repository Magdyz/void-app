package com.void.slate.storage

/**
 * Interface for secure storage.
 * All block data goes through this interface for encrypted persistence.
 *
 * Implementation uses SQLCipher for database encryption with keys from Android Keystore.
 */
interface SecureStorage {
    /**
     * Store binary data.
     */
    suspend fun put(key: String, value: ByteArray)

    /**
     * Retrieve binary data.
     */
    suspend fun get(key: String): ByteArray?

    /**
     * Store string data.
     */
    suspend fun putString(key: String, value: String)

    /**
     * Retrieve string data.
     */
    suspend fun getString(key: String): String?

    /**
     * Delete data by key.
     */
    suspend fun delete(key: String)

    /**
     * Check if key exists.
     */
    suspend fun contains(key: String): Boolean

    /**
     * Get a device-specific identifier for key derivation.
     * This is stable across app restarts but unique per device.
     */
    suspend fun getDeviceId(): ByteArray

    /**
     * Clear all data (for testing or factory reset).
     */
    suspend fun clear()
}
