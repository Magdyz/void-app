package com.void.slate.storage

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for SecureStorage contract.
 * Uses FakeSecureStorage for testing without Android dependencies.
 *
 * These tests validate the contract that SqlCipherStorage must also satisfy.
 */
class SecureStorageTest {

    private lateinit var storage: FakeSecureStorage

    @BeforeEach
    fun setup() {
        storage = FakeSecureStorage()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Binary Data Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `stored binary data is retrievable`() = runTest {
        val key = "test.key"
        val value = "secret data".toByteArray()

        storage.put(key, value)
        val retrieved = storage.get(key)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `binary data can be updated`() = runTest {
        val key = "test.key"
        val value1 = "first value".toByteArray()
        val value2 = "second value".toByteArray()

        storage.put(key, value1)
        storage.put(key, value2)

        val retrieved = storage.get(key)
        assertThat(retrieved).isEqualTo(value2)
    }

    @Test
    fun `get returns null for missing key`() = runTest {
        val retrieved = storage.get("missing.key")
        assertThat(retrieved).isNull()
    }

    @Test
    fun `stored binary data is copied (not referenced)`() = runTest {
        val key = "test.key"
        val value = byteArrayOf(1, 2, 3)

        storage.put(key, value)
        value[0] = 99 // Modify original

        val retrieved = storage.get(key)
        assertThat(retrieved?.get(0)).isEqualTo(1) // Should still be 1
    }

    // ═══════════════════════════════════════════════════════════════════
    // String Data Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `stored string data is retrievable`() = runTest {
        val key = "test.string"
        val value = "Hello, VOID!"

        storage.putString(key, value)
        val retrieved = storage.getString(key)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `string data can be updated`() = runTest {
        val key = "test.string"
        val value1 = "first"
        val value2 = "second"

        storage.putString(key, value1)
        storage.putString(key, value2)

        val retrieved = storage.getString(key)
        assertThat(retrieved).isEqualTo(value2)
    }

    @Test
    fun `getString returns null for missing key`() = runTest {
        val retrieved = storage.getString("missing.key")
        assertThat(retrieved).isNull()
    }

    @Test
    fun `string handles unicode correctly`() = runTest {
        val key = "unicode.test"
        val value = "Hello 世界 🌍 Привет"

        storage.putString(key, value)
        val retrieved = storage.getString(key)

        assertThat(retrieved).isEqualTo(value)
    }

    @Test
    fun `string handles empty string`() = runTest {
        val key = "empty.test"
        val value = ""

        storage.putString(key, value)
        val retrieved = storage.getString(key)

        assertThat(retrieved).isEqualTo(value)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Delete Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `deleted data returns null`() = runTest {
        val key = "test.key"
        storage.put(key, "value".toByteArray())

        storage.delete(key)

        assertThat(storage.get(key)).isNull()
    }

    @Test
    fun `deleting non-existent key does not error`() = runTest {
        storage.delete("missing.key") // Should not throw
    }

    @Test
    fun `delete removes key from contains check`() = runTest {
        val key = "test.key"
        storage.put(key, "value".toByteArray())
        assertThat(storage.contains(key)).isTrue()

        storage.delete(key)
        assertThat(storage.contains(key)).isFalse()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Contains Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `contains returns correct status`() = runTest {
        val key = "test.key"

        assertThat(storage.contains(key)).isFalse()

        storage.put(key, "data".toByteArray())
        assertThat(storage.contains(key)).isTrue()
    }

    @Test
    fun `contains works for string data`() = runTest {
        val key = "test.string"

        assertThat(storage.contains(key)).isFalse()

        storage.putString(key, "data")
        assertThat(storage.contains(key)).isTrue()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Device ID Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `getDeviceId returns consistent value`() = runTest {
        val deviceId1 = storage.getDeviceId()
        val deviceId2 = storage.getDeviceId()

        assertThat(deviceId1).isEqualTo(deviceId2)
    }

    @Test
    fun `getDeviceId returns 32 bytes`() = runTest {
        val deviceId = storage.getDeviceId()
        assertThat(deviceId).hasLength(32)
    }

    @Test
    fun `different storage instances have different device IDs`() = runTest {
        val storage1 = FakeSecureStorage()
        val storage2 = FakeSecureStorage()

        val deviceId1 = storage1.getDeviceId()
        val deviceId2 = storage2.getDeviceId()

        assertThat(deviceId1).isNotEqualTo(deviceId2)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Clear Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `clear removes all data`() = runTest {
        storage.put("key1", "value1".toByteArray())
        storage.put("key2", "value2".toByteArray())
        storage.putString("key3", "value3")

        storage.clear()

        assertThat(storage.contains("key1")).isFalse()
        assertThat(storage.contains("key2")).isFalse()
        assertThat(storage.contains("key3")).isFalse()
        assertThat(storage.size()).isEqualTo(0)
    }

    @Test
    fun `clear on empty storage does not error`() = runTest {
        storage.clear() // Should not throw
    }

    // ═══════════════════════════════════════════════════════════════════
    // Integration Tests - Simulating Real Usage
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `identity repository pattern - store and retrieve encrypted seed`() = runTest {
        // Simulate how IdentityRepository uses SecureStorage
        val seedKey = "identity.seed"
        val nonceKey = "identity.nonce"
        val wordsKey = "identity.words"

        val encryptedSeed = byteArrayOf(1, 2, 3, 4, 5)
        val nonce = byteArrayOf(6, 7, 8, 9, 10)
        val words = "apple,banana,cherry"

        // Store
        storage.put(seedKey, encryptedSeed)
        storage.put(nonceKey, nonce)
        storage.putString(wordsKey, words)

        // Retrieve
        assertThat(storage.get(seedKey)).isEqualTo(encryptedSeed)
        assertThat(storage.get(nonceKey)).isEqualTo(nonce)
        assertThat(storage.getString(wordsKey)).isEqualTo(words)
    }

    @Test
    fun `rhythm repository pattern - store and verify template`() = runTest {
        // Simulate how RhythmRepository uses SecureStorage
        val saltKey = "rhythm.salt"
        val templateKey = "rhythm.template.cipher"
        val nonceKey = "rhythm.template.nonce"

        val salt = ByteArray(32) { it.toByte() }
        val template = ByteArray(100) { it.toByte() }
        val nonce = ByteArray(12) { it.toByte() }

        // Store
        storage.put(saltKey, salt)
        storage.put(templateKey, template)
        storage.put(nonceKey, nonce)

        // Check if rhythm is registered
        val hasRhythm = storage.contains(saltKey)
        assertThat(hasRhythm).isTrue()

        // Retrieve for verification
        assertThat(storage.get(saltKey)).isEqualTo(salt)
        assertThat(storage.get(templateKey)).isEqualTo(template)
        assertThat(storage.get(nonceKey)).isEqualTo(nonce)
    }

    @Test
    fun `namespace isolation - different prefixes don't collide`() = runTest {
        // Blocks should use namespaced keys
        storage.put("identity.seed", "identity-data".toByteArray())
        storage.put("rhythm.seed", "rhythm-data".toByteArray())
        storage.put("messaging.key", "messaging-data".toByteArray())

        assertThat(storage.get("identity.seed"))
            .isEqualTo("identity-data".toByteArray())
        assertThat(storage.get("rhythm.seed"))
            .isEqualTo("rhythm-data".toByteArray())
        assertThat(storage.get("messaging.key"))
            .isEqualTo("messaging-data".toByteArray())
    }
}
