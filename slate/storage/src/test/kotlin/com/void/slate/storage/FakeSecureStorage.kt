package com.void.slate.storage

import java.security.SecureRandom

/**
 * In-memory fake implementation of SecureStorage for testing.
 * This allows blocks to be tested without requiring Android Context.
 */
class FakeSecureStorage : SecureStorage {

    private val data = mutableMapOf<String, ByteArray>()
    private val deviceId: ByteArray = ByteArray(32).apply {
        SecureRandom().nextBytes(this)
    }

    override suspend fun put(key: String, value: ByteArray) {
        data[key] = value.copyOf()
    }

    override suspend fun get(key: String): ByteArray? {
        return data[key]?.copyOf()
    }

    override suspend fun putString(key: String, value: String) {
        data[key] = value.toByteArray(Charsets.UTF_8)
    }

    override suspend fun getString(key: String): String? {
        return data[key]?.toString(Charsets.UTF_8)
    }

    override suspend fun delete(key: String) {
        data.remove(key)
    }

    override suspend fun contains(key: String): Boolean {
        return data.containsKey(key)
    }

    override suspend fun getDeviceId(): ByteArray {
        return deviceId.copyOf()
    }

    override suspend fun clear() {
        data.clear()
    }

    /**
     * Get all stored keys (for testing).
     */
    fun getAllKeys(): Set<String> = data.keys.toSet()

    /**
     * Get the number of stored items (for testing).
     */
    fun size(): Int = data.size
}
