package com.void.slate.crypto

import com.google.common.truth.Truth.assertThat
import com.void.slate.crypto.impl.TinkCryptoProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for TinkCryptoProvider.
 * Validates all cryptographic operations according to Phase 1A requirements.
 */
class TinkCryptoProviderTest {

    private lateinit var crypto: TinkCryptoProvider

    @BeforeEach
    fun setup() {
        crypto = TinkCryptoProvider()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Seed Generation Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `generateSeed produces correct byte length`() = runTest {
        val seed = crypto.generateSeed(32)
        assertThat(seed).hasLength(32)
    }

    @Test
    fun `generateSeed produces different values each time`() = runTest {
        val seed1 = crypto.generateSeed(32)
        val seed2 = crypto.generateSeed(32)

        assertThat(seed1).isNotEqualTo(seed2)
    }

    @Test
    fun `generateSeed supports different sizes`() = runTest {
        val seed16 = crypto.generateSeed(16)
        val seed32 = crypto.generateSeed(32)
        val seed64 = crypto.generateSeed(64)

        assertThat(seed16).hasLength(16)
        assertThat(seed32).hasLength(32)
        assertThat(seed64).hasLength(64)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Key Derivation Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `derive produces deterministic output`() = runTest {
        val seed = crypto.generateSeed(32)
        val path = "test/path"

        val derived1 = crypto.derive(seed, path)
        val derived2 = crypto.derive(seed, path)

        assertThat(derived1).isEqualTo(derived2)
    }

    @Test
    fun `derive produces different output for different paths`() = runTest {
        val seed = crypto.generateSeed(32)

        val derived1 = crypto.derive(seed, "path/1")
        val derived2 = crypto.derive(seed, "path/2")

        assertThat(derived1).isNotEqualTo(derived2)
    }

    @Test
    fun `derive produces different output for different seeds`() = runTest {
        val seed1 = crypto.generateSeed(32)
        val seed2 = crypto.generateSeed(32)
        val path = "same/path"

        val derived1 = crypto.derive(seed1, path)
        val derived2 = crypto.derive(seed2, path)

        assertThat(derived1).isNotEqualTo(derived2)
    }

    @Test
    fun `derive produces 32-byte output`() = runTest {
        val seed = crypto.generateSeed(32)
        val derived = crypto.derive(seed, "test")

        assertThat(derived).hasLength(32)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Hashing Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `hash produces deterministic output`() = runTest {
        val data = "Hello, VOID!".toByteArray()

        val hash1 = crypto.hash(data)
        val hash2 = crypto.hash(data)

        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `hash produces different output for different input`() = runTest {
        val data1 = "Hello, VOID!".toByteArray()
        val data2 = "Goodbye, VOID!".toByteArray()

        val hash1 = crypto.hash(data1)
        val hash2 = crypto.hash(data2)

        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `hash produces 32-byte output (SHA-256)`() = runTest {
        val data = "test".toByteArray()
        val hash = crypto.hash(data)

        assertThat(hash).hasLength(32)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Encryption/Decryption Tests - Core Success Criteria
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `encrypt then decrypt returns original plaintext`() = runTest {
        val key = crypto.generateSeed(32)
        val plaintext = "Hello, VOID! This is a secret message.".toByteArray()

        val encrypted = crypto.encrypt(plaintext, key)
        val decrypted = crypto.decrypt(encrypted, key)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `encrypt produces different ciphertext each time (random nonce)`() = runTest {
        val key = crypto.generateSeed(32)
        val plaintext = "Same message".toByteArray()

        val encrypted1 = crypto.encrypt(plaintext, key)
        val encrypted2 = crypto.encrypt(plaintext, key)

        // Ciphertext should be different due to random nonce
        assertThat(encrypted1.ciphertext).isNotEqualTo(encrypted2.ciphertext)
        assertThat(encrypted1.nonce).isNotEqualTo(encrypted2.nonce)

        // But both should decrypt to the same plaintext
        val decrypted1 = crypto.decrypt(encrypted1, key)
        val decrypted2 = crypto.decrypt(encrypted2, key)
        assertThat(decrypted1).isEqualTo(plaintext)
        assertThat(decrypted2).isEqualTo(plaintext)
    }

    @Test
    fun `decrypt with wrong key throws exception`() = runTest {
        val correctKey = crypto.generateSeed(32)
        val wrongKey = crypto.generateSeed(32)
        val plaintext = "Secret".toByteArray()

        val encrypted = crypto.encrypt(plaintext, correctKey)

        assertThrows<RuntimeException> {
            crypto.decrypt(encrypted, wrongKey)
        }
    }

    @Test
    fun `encrypt with invalid key size throws exception`() = runTest {
        val invalidKey = crypto.generateSeed(16) // Only 16 bytes, should be 32
        val plaintext = "test".toByteArray()

        assertThrows<IllegalArgumentException> {
            crypto.encrypt(plaintext, invalidKey)
        }
    }

    @Test
    fun `decrypt with invalid key size throws exception`() = runTest {
        val correctKey = crypto.generateSeed(32)
        val invalidKey = crypto.generateSeed(16)
        val plaintext = "test".toByteArray()

        val encrypted = crypto.encrypt(plaintext, correctKey)

        assertThrows<IllegalArgumentException> {
            crypto.decrypt(encrypted, invalidKey)
        }
    }

    @Test
    fun `encrypted data has non-null nonce`() = runTest {
        val key = crypto.generateSeed(32)
        val plaintext = "test".toByteArray()

        val encrypted = crypto.encrypt(plaintext, key)

        assertThat(encrypted.nonce).isNotNull()
        assertThat(encrypted.nonce).hasLength(12) // GCM standard nonce size
    }

    @Test
    fun `encrypt handles empty plaintext`() = runTest {
        val key = crypto.generateSeed(32)
        val plaintext = ByteArray(0)

        val encrypted = crypto.encrypt(plaintext, key)
        val decrypted = crypto.decrypt(encrypted, key)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `encrypt handles large plaintext`() = runTest {
        val key = crypto.generateSeed(32)
        val plaintext = ByteArray(1024 * 1024) { it.toByte() } // 1 MB

        val encrypted = crypto.encrypt(plaintext, key)
        val decrypted = crypto.decrypt(encrypted, key)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Key Pair Generation Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `generateKeyPair produces non-empty keys`() = runTest {
        val keyPair = crypto.generateKeyPair()

        assertThat(keyPair.publicKey).isNotEmpty()
        assertThat(keyPair.privateKey).isNotEmpty()
    }

    @Test
    fun `generateKeyPair produces different keys each time`() = runTest {
        val keyPair1 = crypto.generateKeyPair()
        val keyPair2 = crypto.generateKeyPair()

        assertThat(keyPair1.publicKey).isNotEqualTo(keyPair2.publicKey)
        assertThat(keyPair1.privateKey).isNotEqualTo(keyPair2.privateKey)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Signature Tests (Phase 2 Preparation)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `sign produces non-empty signature`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val data = "Message to sign".toByteArray()

        val signature = crypto.sign(data, keyPair.privateKey)

        assertThat(signature).isNotEmpty()
    }

    @Test
    fun `verify returns true for valid signature`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val data = "Message to sign".toByteArray()

        val signature = crypto.sign(data, keyPair.privateKey)
        val isValid = crypto.verify(data, signature, keyPair.publicKey)

        assertThat(isValid).isTrue()
    }

    @Test
    fun `verify returns false for invalid signature`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val data = "Message to sign".toByteArray()
        val invalidSignature = ByteArray(64) { 0 } // Random invalid signature

        val isValid = crypto.verify(data, invalidSignature, keyPair.publicKey)

        assertThat(isValid).isFalse()
    }

    @Test
    fun `verify returns false when data is tampered`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val originalData = "Original message".toByteArray()
        val tamperedData = "Tampered message".toByteArray()

        val signature = crypto.sign(originalData, keyPair.privateKey)
        val isValid = crypto.verify(tamperedData, signature, keyPair.publicKey)

        assertThat(isValid).isFalse()
    }

    @Test
    fun `verify returns false with wrong public key`() = runTest {
        val keyPair1 = crypto.generateKeyPair()
        val keyPair2 = crypto.generateKeyPair()
        val data = "Message".toByteArray()

        val signature = crypto.sign(data, keyPair1.privateKey)
        val isValid = crypto.verify(data, signature, keyPair2.publicKey)

        assertThat(isValid).isFalse()
    }

    @Test
    fun `sign produces deterministic signature for same data`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val data = "Consistent message".toByteArray()

        val signature1 = crypto.sign(data, keyPair.privateKey)
        val signature2 = crypto.sign(data, keyPair.privateKey)

        // Ed25519 signatures are deterministic
        assertThat(signature1).isEqualTo(signature2)
    }

    @Test
    fun `sign produces different signatures for different data`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val data1 = "Message 1".toByteArray()
        val data2 = "Message 2".toByteArray()

        val signature1 = crypto.sign(data1, keyPair.privateKey)
        val signature2 = crypto.sign(data2, keyPair.privateKey)

        assertThat(signature1).isNotEqualTo(signature2)
    }

    @Test
    fun `verify handles empty data`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val emptyData = ByteArray(0)

        val signature = crypto.sign(emptyData, keyPair.privateKey)
        val isValid = crypto.verify(emptyData, signature, keyPair.publicKey)

        assertThat(isValid).isTrue()
    }

    @Test
    fun `verify handles large data`() = runTest {
        val keyPair = crypto.generateKeyPair()
        val largeData = ByteArray(1024 * 1024) { it.toByte() } // 1 MB

        val signature = crypto.sign(largeData, keyPair.privateKey)
        val isValid = crypto.verify(largeData, signature, keyPair.publicKey)

        assertThat(isValid).isTrue()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Integration Tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `full identity generation flow`() = runTest {
        // Simulate identity block flow
        val masterSeed = crypto.generateSeed(32)
        val identityKey = crypto.derive(masterSeed, "identity/v1")
        val encryptionKey = crypto.derive(masterSeed, "encryption/v1")

        // Identity key should be deterministic
        val identityKey2 = crypto.derive(masterSeed, "identity/v1")
        assertThat(identityKey).isEqualTo(identityKey2)

        // Encryption key should be different from identity key
        assertThat(encryptionKey).isNotEqualTo(identityKey)

        // Should be able to encrypt/decrypt with derived key
        val secret = "My 3-word identity".toByteArray()
        val encrypted = crypto.encrypt(secret, encryptionKey)
        val decrypted = crypto.decrypt(encrypted, encryptionKey)
        assertThat(decrypted).isEqualTo(secret)
    }

    @Test
    fun `full rhythm key derivation flow`() = runTest {
        // Simulate rhythm block flow
        val rhythmSeed = crypto.generateSeed(32)
        val salt = crypto.generateSeed(32)

        // Derive key for rhythm encryption
        val rhythmKey = crypto.derive(rhythmSeed, "rhythm/v1")

        // Store encrypted template
        val template = "rhythm-template-data".toByteArray()
        val encrypted = crypto.encrypt(template, rhythmKey)

        // Later verification - same rhythm produces same key
        val verifyKey = crypto.derive(rhythmSeed, "rhythm/v1")
        assertThat(verifyKey).isEqualTo(rhythmKey)

        // Can decrypt template
        val decrypted = crypto.decrypt(encrypted, verifyKey)
        assertThat(decrypted).isEqualTo(template)
    }

    @Test
    fun `full identity verification flow for Phase 2 networking`() = runTest {
        // Simulate Phase 2 use case: User A sends a message to User B
        // User A must prove their identity cryptographically

        // User A generates their identity keypair
        val userAKeyPair = crypto.generateKeyPair()
        val userAIdentity = "alpha.bravo.charlie" // 3-word identity

        // User A creates a message and signs it
        val message = "Hello from User A!".toByteArray()
        val signature = crypto.sign(message, userAKeyPair.privateKey)

        // User B receives: message, signature, and User A's public key
        // User B verifies the message came from User A
        val isValidSignature = crypto.verify(message, signature, userAKeyPair.publicKey)
        assertThat(isValidSignature).isTrue()

        // If someone tampers with the message, verification fails
        val tamperedMessage = "Hello from User B!".toByteArray()
        val isTamperedValid = crypto.verify(tamperedMessage, signature, userAKeyPair.publicKey)
        assertThat(isTamperedValid).isFalse()

        // If someone tries to impersonate User A with a different keypair, verification fails
        val attackerKeyPair = crypto.generateKeyPair()
        val isImpersonatorValid = crypto.verify(message, signature, attackerKeyPair.publicKey)
        assertThat(isImpersonatorValid).isFalse()
    }
}
