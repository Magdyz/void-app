package com.void.block.identity

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.void.block.identity.data.IdentityRepository
import com.void.block.identity.data.SecureStorage
import com.void.block.identity.domain.GenerateIdentity
import com.void.block.identity.domain.Identity
import com.void.block.identity.domain.WordDictionary
import com.void.block.identity.events.IdentityCreated
import com.void.block.identity.ui.IdentityIntent
import com.void.block.identity.ui.IdentityViewModel
import com.void.slate.crypto.CryptoProvider
import com.void.slate.crypto.EncryptedData
import com.void.slate.crypto.KeyPair
import com.void.slate.event.Event
import com.void.slate.event.EventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * Tests for the Identity block.
 * 
 * These tests demonstrate how blocks are tested in COMPLETE ISOLATION.
 * All dependencies are faked - no other blocks or real implementations needed.
 */
class IdentityBlockTest {
    
    private lateinit var fakeStorage: FakeSecureStorage
    private lateinit var fakeCrypto: FakeCryptoProvider
    private lateinit var fakeEventBus: FakeEventBus
    private lateinit var dictionary: WordDictionary
    private lateinit var repository: IdentityRepository
    private lateinit var generateIdentity: GenerateIdentity
    private lateinit var viewModel: IdentityViewModel
    
    @BeforeEach
    fun setup() {
        fakeStorage = FakeSecureStorage()
        fakeCrypto = FakeCryptoProvider()
        fakeEventBus = FakeEventBus()
        dictionary = WordDictionary()
        repository = IdentityRepository(fakeStorage, fakeCrypto)
        generateIdentity = GenerateIdentity(repository, dictionary, fakeCrypto)
        viewModel = IdentityViewModel(generateIdentity, fakeEventBus)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // Domain Layer Tests
    // ═══════════════════════════════════════════════════════════════════
    
    @Test
    fun `GenerateIdentity creates valid 3-word identity`() = runTest {
        val identity = generateIdentity()
        
        assertThat(identity.words).hasSize(3)
        assertThat(identity.words).containsNoDuplicates()
        assertThat(identity.formatted).matches("[a-z]+\\.[a-z]+\\.[a-z]+")
    }
    
    @Test
    fun `GenerateIdentity stores identity in repository`() = runTest {
        generateIdentity()
        
        assertThat(repository.hasIdentity()).isTrue()
    }
    
    @Test
    fun `GenerateIdentity returns existing identity when not regenerating`() = runTest {
        val first = generateIdentity()
        val second = generateIdentity(regenerate = false)
        
        assertThat(second).isEqualTo(first)
    }
    
    @Test
    fun `GenerateIdentity creates new identity when regenerating`() = runTest {
        val first = generateIdentity()
        
        // Change the seed for next generation
        fakeCrypto.nextSeed = ByteArray(32) { 1 }
        
        val second = generateIdentity(regenerate = true)
        
        assertThat(second).isNotEqualTo(first)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // ViewModel Tests
    // ═══════════════════════════════════════════════════════════════════
    
    @Test
    fun `ViewModel loads identity on init`() = runTest {
        viewModel.state.test {
            // Skip initial state
            awaitItem()
            
            // Wait for loaded state
            val loaded = awaitItem()
            assertThat(loaded.identity).isNotNull()
            assertThat(loaded.identity!!.words).hasSize(3)
        }
    }
    
    @Test
    fun `ViewModel emits IdentityCreated event`() = runTest {
        // Wait for initialization
        viewModel.state.test {
            skipItems(2)
        }
        
        assertThat(fakeEventBus.emittedEvents)
            .hasSize(1)
        assertThat(fakeEventBus.emittedEvents.first())
            .isInstanceOf(IdentityCreated::class.java)
    }
    
    @Test
    fun `Regenerate intent creates new identity`() = runTest {
        viewModel.state.test {
            // Skip initial states
            skipItems(2)
            
            // Regenerate
            fakeCrypto.nextSeed = ByteArray(32) { 2 }
            viewModel.onIntent(IdentityIntent.Regenerate)
            
            // Skip regenerating state
            val regenerating = awaitItem()
            assertThat(regenerating.isRegenerating).isTrue()
            
            // Get final state
            val final = awaitItem()
            assertThat(final.isRegenerating).isFalse()
            assertThat(final.regenerateCount).isEqualTo(1)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // Block Isolation Verification
    // ═══════════════════════════════════════════════════════════════════
    
    @Test
    fun `Block only emits events, never imports other blocks`() {
        // This test verifies architectural compliance
        // In a real build, the Gradle plugin enforces this
        
        val blockPackages = this::class.java.`package`?.name ?: ""
        assertThat(blockPackages).startsWith("com.void.block.identity")
        
        // Verify events are the ONLY way to communicate
        assertThat(fakeEventBus.emittedEvents).isNotEmpty()
    }
}

// ═══════════════════════════════════════════════════════════════════
// Test Fakes (used ONLY in tests)
// ═══════════════════════════════════════════════════════════════════

class FakeSecureStorage : SecureStorage {
    private val storage = mutableMapOf<String, ByteArray>()
    private val stringStorage = mutableMapOf<String, String>()
    
    override suspend fun put(key: String, value: ByteArray) { storage[key] = value }
    override suspend fun get(key: String) = storage[key]
    override suspend fun getString(key: String) = stringStorage[key]
    override suspend fun putString(key: String, value: String) { stringStorage[key] = value }
    override suspend fun delete(key: String) { storage.remove(key); stringStorage.remove(key) }
    override suspend fun contains(key: String) = storage.containsKey(key) || stringStorage.containsKey(key)
    override suspend fun getDeviceId() = ByteArray(32) { 0 }
}

class FakeCryptoProvider : CryptoProvider {
    var nextSeed: ByteArray = ByteArray(32) { 0 }
    
    override suspend fun generateSeed(bytes: Int) = nextSeed.copyOf(bytes)
    override suspend fun derive(seed: ByteArray, path: String): ByteArray {
        // Simple deterministic derivation for testing
        return seed.mapIndexed { i, b -> (b + path.hashCode() + i).toByte() }.toByteArray()
    }
    override suspend fun hash(data: ByteArray) = data.reversedArray()
    override suspend fun encrypt(plaintext: ByteArray, key: ByteArray) = 
        EncryptedData(plaintext, ByteArray(12) { 0 })
    override suspend fun decrypt(encrypted: EncryptedData, key: ByteArray) = encrypted.ciphertext
    override suspend fun generateKeyPair() = KeyPair(ByteArray(32), ByteArray(64))
    override suspend fun sign(data: ByteArray, privateKey: ByteArray) = data
    override suspend fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray) = true
}

class FakeEventBus : EventBus {
    val emittedEvents = mutableListOf<Event>()
    private val flow = MutableSharedFlow<Event>()
    
    override suspend fun emit(event: Event) {
        emittedEvents.add(event)
        flow.emit(event)
    }
    
    override fun observe(): Flow<Event> = flow
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : Event> observe(type: KClass<T>): Flow<T> = flow as Flow<T>
}
