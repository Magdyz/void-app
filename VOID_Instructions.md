# VOID Development Instructions for Claude Code

> **Project**: VOID - Privacy-First Secure Messaging App
> **Architecture**: Slate + Block (Lego-style modular)
> **Platform**: Android (Kotlin 2.0+, Jetpack Compose)
> **Version**: 2.0 (Revised Rhythm Security Model)

---

## 🎯 How to Use This Document

This document contains step-by-step instructions organized into phases. Each phase:
1. Has clear prerequisites
2. Contains specific implementation tasks
3. Ends with testable success criteria
4. Must pass all tests before moving to the next phase

**Golden Rules:**
- Blocks NEVER import other blocks (use EventBus only)
- All crypto goes through `CryptoProvider` interface
- All storage goes through `SecureStorage` interface
- All key storage goes through `KeystoreManager` (hardware-backed)
- Test each block in isolation with fakes
- Run tests after completing each section

**Critical Security Principle:**
> Rhythm is a GATEKEEPER, not a key source. The rhythm gates access to hardware-backed keys in Android Keystore. Fuzzy matching is safe because it doesn't affect cryptographic security.

---

## 📁 Project Location

The base project with architecture is already created at:
```
/path/to/void-app/
```

Load it and familiarize yourself with:
- `ARCHITECTURE.md` - Overall design philosophy
- `slate/core/` - Core interfaces and contracts
- `blocks/identity/` - Complete example block to follow
- `build-logic/convention/` - Gradle plugins

---

# PHASE 1A: Complete Core Infrastructure

## Prerequisites
- Base project structure exists
- Convention plugins created
- Identity block has basic structure

## Tasks

### 1.1 Complete the BIP-39 Word Dictionary

**File**: `blocks/identity/src/main/kotlin/.../domain/WordDictionary.kt`

**Instructions**:
1. Replace the sample word list with the full 2048-word BIP-39 English wordlist
2. Source: https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt
3. For VOID's 3-word system, we use 2048 words (this gives 8 billion combinations)

```kotlin
// The dictionary should have exactly 2048 words
// Each word should be lowercase, no duplicates
// Words should be 3-8 characters for easy verbal sharing

object WordDictionary {
    val words: List<String> = listOf(
        "abandon", "ability", "able", "about", "above", // ... all 2048 words
    )
    
    val wordCount: Int get() = words.size
    
    fun wordAt(index: Int): String = words[index % wordCount]
    
    fun indexOf(word: String): Int = words.indexOf(word.lowercase())
    
    fun isValidWord(word: String): Boolean = words.contains(word.lowercase())
    
    /**
     * Generate 3-word identity from seed bytes.
     * Uses first 33 bits (11 bits per word).
     */
    fun generateIdentity(seed: ByteArray): ThreeWordIdentity {
        val bits = seed.toBitString()
        val word1 = wordAt(bits.substring(0, 11).toInt(2))
        val word2 = wordAt(bits.substring(11, 22).toInt(2))
        val word3 = wordAt(bits.substring(22, 33).toInt(2))
        return ThreeWordIdentity(word1, word2, word3)
    }
}

data class ThreeWordIdentity(
    val word1: String,
    val word2: String,
    val word3: String
) {
    override fun toString(): String = "$word1.$word2.$word3"
    
    companion object {
        fun parse(identity: String): ThreeWordIdentity? {
            val parts = identity.split(".")
            if (parts.size != 3) return null
            return ThreeWordIdentity(parts[0], parts[1], parts[2])
        }
    }
}
```

**Success Criteria**:
```kotlin
@Test
fun `dictionary has exactly 2048 words`() {
    assertThat(WordDictionary.wordCount).isEqualTo(2048)
}

@Test
fun `all words are unique`() {
    val words = WordDictionary.words
    assertThat(words.distinct().size).isEqualTo(words.size)
}

@Test
fun `all words are lowercase alphabetic`() {
    WordDictionary.words.forEach { word ->
        assertThat(word).matches("[a-z]+")
    }
}

@Test
fun `same seed produces same identity`() {
    val seed = "test_seed".toByteArray().sha256()
    val id1 = WordDictionary.generateIdentity(seed)
    val id2 = WordDictionary.generateIdentity(seed)
    assertThat(id1).isEqualTo(id2)
}
```

---

### 1.2 Implement KeystoreManager (Hardware-Backed Key Storage)

**File**: `slate/crypto/src/main/kotlin/.../KeystoreManager.kt`

**Instructions**:
This is CRITICAL infrastructure. All master keys are stored here, protected by hardware.

```kotlin
/**
 * Manages Android Keystore operations.
 * Provides hardware-backed key storage when available.
 * 
 * SECURITY: Keys generated here NEVER leave the secure hardware.
 */
class KeystoreManager(private val context: Context) {
    
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }
    
    companion object {
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }
    
    /**
     * Generate a new AES-256 key in the Keystore.
     * 
     * @param alias Unique identifier for the key
     * @param requireAuth If true, requires biometric auth before use
     * @param useStrongBox If true, uses dedicated secure element (if available)
     */
    fun generateKey(
        alias: String,
        requireAuth: Boolean = false,
        useStrongBox: Boolean = true
    ): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        
        // Use StrongBox if available (dedicated secure element)
        if (useStrongBox && hasStrongBox()) {
            builder.setIsStrongBoxBacked(true)
        }
        
        // Require user authentication if specified
        if (requireAuth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    30, // Valid for 30 seconds after auth
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
        }
        
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }
    
    /**
     * Check if StrongBox (secure element) is available.
     */
    fun hasStrongBox(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_STRONGBOX_KEYSTORE
            )
        } else {
            false
        }
    }
    
    /**
     * Encrypt data with a Keystore key.
     * Returns IV prepended to ciphertext.
     */
    fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val key = getKey(alias) ?: throw KeyNotFoundException(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        
        // Prepend IV to ciphertext: [IV (12 bytes)][Ciphertext][Tag]
        return ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }
    
    /**
     * Decrypt data with a Keystore key.
     * Expects IV prepended to ciphertext.
     */
    fun decrypt(alias: String, data: ByteArray): ByteArray {
        val key = getKey(alias) ?: throw KeyNotFoundException(alias)
        
        // Extract IV (first 12 bytes for GCM)
        val iv = data.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = data.sliceArray(GCM_IV_LENGTH until data.size)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        
        return cipher.doFinal(ciphertext)
    }
    
    /**
     * Get a key from the Keystore.
     */
    fun getKey(alias: String): SecretKey? {
        return keyStore.getKey(alias, null) as? SecretKey
    }
    
    /**
     * Check if a key exists.
     */
    fun hasKey(alias: String): Boolean {
        return keyStore.containsAlias(alias)
    }
    
    /**
     * Delete a key.
     */
    fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }
    
    /**
     * Delete all VOID keys (for panic wipe).
     */
    fun deleteAllVoidKeys() {
        keyStore.aliases().toList()
            .filter { it.startsWith("void_") }
            .forEach { keyStore.deleteEntry(it) }
    }
}

class KeyNotFoundException(alias: String) : Exception("Key not found: $alias")
```

**Dependencies** in `slate/crypto/build.gradle.kts`:
```kotlin
dependencies {
    implementation(libs.androidx.security.crypto)
}
```

**Success Criteria**:
```kotlin
@Test
fun `generate and retrieve key`() {
    val manager = KeystoreManager(context)
    manager.generateKey("test_key")
    
    assertThat(manager.hasKey("test_key")).isTrue()
    assertThat(manager.getKey("test_key")).isNotNull()
}

@Test
fun `encrypt then decrypt returns original`() {
    val manager = KeystoreManager(context)
    manager.generateKey("test_key")
    
    val plaintext = "Hello VOID".toByteArray()
    val encrypted = manager.encrypt("test_key", plaintext)
    val decrypted = manager.decrypt("test_key", encrypted)
    
    assertThat(decrypted).isEqualTo(plaintext)
}

@Test
fun `delete key removes it`() {
    val manager = KeystoreManager(context)
    manager.generateKey("temp_key")
    
    manager.deleteKey("temp_key")
    
    assertThat(manager.hasKey("temp_key")).isFalse()
}

@Test
fun `decrypt with wrong key fails`() {
    val manager = KeystoreManager(context)
    manager.generateKey("key_a")
    manager.generateKey("key_b")
    
    val encrypted = manager.encrypt("key_a", "secret".toByteArray())
    
    assertThrows<AEADBadTagException> {
        manager.decrypt("key_b", encrypted)
    }
}
```

---

### 1.3 Implement TinkCryptoProvider

**File**: `slate/crypto/src/main/kotlin/.../TinkCryptoProvider.kt`

**Instructions**:
1. Create implementation of `CryptoProvider` interface using Google Tink
2. This is for general crypto operations, NOT master key storage (that's Keystore)

```kotlin
/**
 * CryptoProvider implementation using Google Tink.
 * Used for general encryption operations with provided keys.
 * 
 * NOTE: For master key storage, use KeystoreManager instead.
 */
class TinkCryptoProvider : CryptoProvider {
    
    init {
        AeadConfig.register()
        MacConfig.register()
    }
    
    private val secureRandom = SecureRandom()
    
    override suspend fun generateRandomBytes(size: Int): ByteArray {
        return ByteArray(size).also { secureRandom.nextBytes(it) }
    }
    
    override suspend fun hash(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }
    
    override suspend fun hmac(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
    
    override suspend fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptedData {
        val keysetHandle = KeysetHandle.generateNew(
            KeyTemplates.get("AES256_GCM")
        )
        // For simplicity, use raw AES-GCM here
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        return EncryptedData(
            ciphertext = cipher.doFinal(plaintext),
            nonce = cipher.iv
        )
    }
    
    override suspend fun decrypt(encrypted: EncryptedData, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, encrypted.nonce))
        
        return cipher.doFinal(encrypted.ciphertext)
    }
    
    override suspend fun deriveKey(
        seed: ByteArray,
        salt: ByteArray,
        info: String,
        outputLength: Int
    ): ByteArray {
        // HKDF implementation
        return Hkdf.computeHkdf(
            "HMACSHA256",
            seed,
            salt,
            info.toByteArray(),
            outputLength
        )
    }
}

data class EncryptedData(
    val ciphertext: ByteArray,
    val nonce: ByteArray
) {
    fun toByteArray(): ByteArray = nonce + ciphertext
    
    companion object {
        fun fromByteArray(data: ByteArray, nonceLength: Int = 12): EncryptedData {
            return EncryptedData(
                nonce = data.sliceArray(0 until nonceLength),
                ciphertext = data.sliceArray(nonceLength until data.size)
            )
        }
    }
}
```

**Dependencies** in `slate/crypto/build.gradle.kts`:
```kotlin
dependencies {
    implementation(libs.tink)
}
```

**Success Criteria**:
```kotlin
@Test
fun `encrypt then decrypt returns original`() = runTest {
    val crypto = TinkCryptoProvider()
    val key = crypto.generateRandomBytes(32)
    val plaintext = "Hello VOID".toByteArray()
    
    val encrypted = crypto.encrypt(plaintext, key)
    val decrypted = crypto.decrypt(encrypted, key)
    
    assertThat(decrypted).isEqualTo(plaintext)
}

@Test
fun `hash is deterministic`() = runTest {
    val crypto = TinkCryptoProvider()
    val data = "test data".toByteArray()
    
    val hash1 = crypto.hash(data)
    val hash2 = crypto.hash(data)
    
    assertThat(hash1).isEqualTo(hash2)
}

@Test
fun `deriveKey is deterministic`() = runTest {
    val crypto = TinkCryptoProvider()
    val seed = "seed".toByteArray()
    val salt = "salt".toByteArray()
    
    val key1 = crypto.deriveKey(seed, salt, "info", 32)
    val key2 = crypto.deriveKey(seed, salt, "info", 32)
    
    assertThat(key1).isEqualTo(key2)
}
```

---

### 1.4 Implement SecureStorage with SQLCipher

**File**: `slate/storage/src/main/kotlin/.../SqlCipherStorage.kt`

**Instructions**:
1. Implement `SecureStorage` interface
2. Use SQLCipher for encrypted database
3. Database key comes from KeystoreManager

```kotlin
/**
 * Encrypted key-value storage using SQLCipher.
 * The database encryption key is protected by Android Keystore.
 */
class SqlCipherStorage(
    private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val databaseName: String = "void_secure.db"
) : SecureStorage {
    
    companion object {
        private const val KEYSTORE_ALIAS = "void_db_key"
        private const val TABLE_NAME = "secure_kv"
        private const val COL_KEY = "k"
        private const val COL_VALUE = "v"
    }
    
    private val database: SQLiteDatabase by lazy {
        initDatabase()
    }
    
    private fun initDatabase(): SQLiteDatabase {
        SQLiteDatabase.loadLibs(context)
        
        val dbKey = getOrCreateDatabaseKey()
        val dbFile = context.getDatabasePath(databaseName)
        dbFile.parentFile?.mkdirs()
        
        val db = SQLiteDatabase.openOrCreateDatabase(
            dbFile,
            dbKey,
            null,
            null
        )
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_KEY TEXT PRIMARY KEY,
                $COL_VALUE BLOB NOT NULL
            )
        """)
        
        return db
    }
    
    private fun getOrCreateDatabaseKey(): String {
        return if (keystoreManager.hasKey(KEYSTORE_ALIAS)) {
            // Retrieve stored key
            val encryptedKey = context.getSharedPreferences("void_db", Context.MODE_PRIVATE)
                .getString("encrypted_db_key", null)
                ?: throw IllegalStateException("DB key reference missing")
            
            val keyBytes = keystoreManager.decrypt(KEYSTORE_ALIAS, encryptedKey.decodeBase64())
            keyBytes.toHexString()
        } else {
            // Generate new key
            keystoreManager.generateKey(KEYSTORE_ALIAS, useStrongBox = true)
            
            val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val encryptedKey = keystoreManager.encrypt(KEYSTORE_ALIAS, keyBytes)
            
            context.getSharedPreferences("void_db", Context.MODE_PRIVATE)
                .edit()
                .putString("encrypted_db_key", encryptedKey.encodeBase64())
                .apply()
            
            keyBytes.toHexString()
        }
    }
    
    override suspend fun put(key: String, value: ByteArray) = withContext(Dispatchers.IO) {
        database.insertWithOnConflict(
            TABLE_NAME,
            null,
            ContentValues().apply {
                put(COL_KEY, key)
                put(COL_VALUE, value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
    
    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        database.query(
            TABLE_NAME,
            arrayOf(COL_VALUE),
            "$COL_KEY = ?",
            arrayOf(key),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getBlob(0)
            } else {
                null
            }
        }
    }
    
    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        database.delete(TABLE_NAME, "$COL_KEY = ?", arrayOf(key))
        Unit
    }
    
    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        database.query(
            TABLE_NAME,
            arrayOf(COL_KEY),
            "$COL_KEY = ?",
            arrayOf(key),
            null, null, null
        ).use { it.count > 0 }
    }
    
    override suspend fun clear() = withContext(Dispatchers.IO) {
        database.delete(TABLE_NAME, null, null)
        Unit
    }
    
    override suspend fun getAllKeys(): List<String> = withContext(Dispatchers.IO) {
        database.query(TABLE_NAME, arrayOf(COL_KEY), null, null, null, null, null)
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }
    }
}

// Extension functions
private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
```

**Dependencies** in `slate/storage/build.gradle.kts`:
```kotlin
dependencies {
    implementation(libs.sqlcipher)
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
}
```

**Success Criteria**:
```kotlin
@Test
fun `stored data is retrievable`() = runTest {
    val storage = createTestStorage()
    val key = "test.key"
    val value = "secret data".toByteArray()
    
    storage.put(key, value)
    val retrieved = storage.get(key)
    
    assertThat(retrieved).isEqualTo(value)
}

@Test
fun `deleted data returns null`() = runTest {
    val storage = createTestStorage()
    storage.put("key", "value".toByteArray())
    storage.delete("key")
    
    assertThat(storage.get("key")).isNull()
}

@Test
fun `contains returns correct status`() = runTest {
    val storage = createTestStorage()
    
    assertThat(storage.contains("missing")).isFalse()
    storage.put("present", "data".toByteArray())
    assertThat(storage.contains("present")).isTrue()
}

@Test
fun `clear removes all data`() = runTest {
    val storage = createTestStorage()
    storage.put("key1", "value1".toByteArray())
    storage.put("key2", "value2".toByteArray())
    
    storage.clear()
    
    assertThat(storage.getAllKeys()).isEmpty()
}
```

---

### 1.5 Create Design System

**Location**: `slate/design/src/main/kotlin/.../`

**Instructions**:
Create the VOID design system with:

1. **Theme** (`theme/VoidTheme.kt`):
```kotlin
@Composable
fun VoidTheme(
    darkTheme: Boolean = true, // Dark by default for privacy
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) VoidDarkColors else VoidLightColors
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = VoidTypography,
        content = content
    )
}
```

2. **Colors** (`theme/VoidColors.kt`):
```kotlin
val VoidDarkColors = darkColorScheme(
    primary = Color(0xFF6B4EFF),         // Deep purple
    onPrimary = Color.White,
    secondary = Color(0xFF03DAC6),        // Teal accent
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE1E1E1),
    background = Color(0xFF000000),
    error = Color(0xFFCF6679)
)
```

3. **Typography** (`theme/VoidTypography.kt`):
```kotlin
val VoidTypography = Typography(
    // Monospace for 3-word identities
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 28.sp,
        letterSpacing = 2.sp
    ),
    // Clean body text
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp
    )
)
```

4. **Components** (`components/`):
   - `VoidButton.kt` - Primary and secondary variants
   - `VoidCard.kt` - For identity display, messages
   - `VoidTextField.kt` - Styled input
   - `VoidTopBar.kt` - App bar with back navigation
   - `IdentityDisplay.kt` - Styled 3-word identity (monospace, dots)
   - `RhythmPad.kt` - Large tap area for rhythm input

**Success Criteria**:
- Theme compiles and applies to app
- Components render correctly in Preview
- Dark theme is default
- All text meets WCAG contrast requirements

---

## Phase 1A Completion Checklist

Run all tests:
```bash
./gradlew :slate:crypto:test
./gradlew :slate:storage:test  
./gradlew :blocks:identity:test
./gradlew :slate:design:testDebugUnitTest
```

All tests must pass before proceeding to Phase 1B.

---

# PHASE 1B: Rhythm Block Implementation (Revised Security Model)

## Prerequisites
- Phase 1A complete
- KeystoreManager working
- TinkCryptoProvider working
- SecureStorage working

## ⚠️ Critical Architecture Note

**Rhythm is a GATEKEEPER, not a key source.**

```
┌─────────────────────────────────────────────────────────────────┐
│                    SECURITY ARCHITECTURE                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐         ┌──────────────────────┐              │
│  │   RHYTHM     │ ──────► │   FUZZY MATCHER      │              │
│  │   INPUT      │         │   (Tolerance: ±25%)  │              │
│  └──────────────┘         └──────────┬───────────┘              │
│                                      │                           │
│                           Match? ────┴──── No Match?            │
│                              │                  │                │
│                              ▼                  ▼                │
│                    ┌─────────────────┐   ┌──────────────┐       │
│                    │ ANDROID KEYSTORE│   │   DENIED     │       │
│                    │ Release MasterKey│   │ (Rate Limit) │       │
│                    └────────┬────────┘   └──────────────┘       │
│                             │                                    │
│                             ▼                                    │
│                    ┌─────────────────┐                          │
│                    │  DECRYPT DATA   │                          │
│                    └─────────────────┘                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

WHY: Cryptographic hashes are EXACT. Fuzzy matching and hashing are
incompatible. By using rhythm as a gatekeeper to hardware-backed keys,
we get BOTH tolerance AND security.
```

## Tasks

### 1.6 Implement Rhythm Data Classes

**File**: `blocks/rhythm/src/main/kotlin/.../domain/RhythmModels.kt`

```kotlin
/**
 * Represents a single tap in the rhythm.
 */
data class RhythmTap(
    val timestamp: Long,           // Milliseconds since rhythm start
    val pressure: Float,           // 0.0 to 1.0 (if available, else 1.0)
    val x: Float,                  // Normalized 0.0 to 1.0
    val y: Float,                  // Normalized 0.0 to 1.0
    val duration: Long             // How long the tap was held (ms)
)

/**
 * Complete rhythm pattern.
 */
data class RhythmPattern(
    val taps: List<RhythmTap>,
    val totalDuration: Long,
    val capturedAt: Long = System.currentTimeMillis()
) {
    /**
     * Extract timing intervals between taps (most important for matching).
     */
    val intervals: List<Long>
        get() = taps.zipWithNext { a, b -> b.timestamp - a.timestamp }
    
    /**
     * Check if pattern meets minimum requirements.
     */
    val isValid: Boolean
        get() = taps.size in MIN_TAPS..MAX_TAPS && totalDuration <= MAX_DURATION_MS
    
    companion object {
        const val MIN_TAPS = 4
        const val MAX_TAPS = 20
        const val MAX_DURATION_MS = 10_000L // 10 seconds max
    }
}

/**
 * Serialization for storing rhythm patterns.
 */
object RhythmSerializer {
    
    fun serialize(pattern: RhythmPattern): ByteArray {
        return Json.encodeToString(pattern).toByteArray()
    }
    
    fun deserialize(bytes: ByteArray): RhythmPattern {
        return Json.decodeFromString(bytes.decodeToString())
    }
}
```

---

### 1.7 Implement Rhythm Capture

**File**: `blocks/rhythm/src/main/kotlin/.../domain/RhythmCapture.kt`

```kotlin
/**
 * Captures rhythm input from user tap events.
 * Thread-safe for use with Compose gesture handlers.
 */
class RhythmCapture {
    
    private val taps = mutableListOf<RhythmTap>()
    private var startTime: Long = 0
    private var isCapturing = false
    
    private val _tapCount = MutableStateFlow(0)
    val tapCount: StateFlow<Int> = _tapCount.asStateFlow()
    
    /**
     * Start a new capture session.
     */
    @Synchronized
    fun start() {
        taps.clear()
        startTime = System.currentTimeMillis()
        isCapturing = true
        _tapCount.value = 0
    }
    
    /**
     * Record a tap event.
     * 
     * @param pressure Tap pressure 0.0-1.0 (use 1.0 if unavailable)
     * @param x Normalized X position 0.0-1.0
     * @param y Normalized Y position 0.0-1.0
     * @param duration How long tap was held in ms
     */
    @Synchronized
    fun recordTap(
        pressure: Float = 1f,
        x: Float = 0.5f,
        y: Float = 0.5f,
        duration: Long = 100L
    ): Boolean {
        if (!isCapturing) return false
        if (taps.size >= RhythmPattern.MAX_TAPS) return false
        
        val timestamp = System.currentTimeMillis() - startTime
        if (timestamp > RhythmPattern.MAX_DURATION_MS) return false
        
        taps.add(RhythmTap(
            timestamp = timestamp,
            pressure = pressure.coerceIn(0f, 1f),
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f),
            duration = duration
        ))
        
        _tapCount.value = taps.size
        return true
    }
    
    /**
     * Finish capturing and return the pattern.
     * Returns null if minimum requirements not met.
     */
    @Synchronized
    fun finish(): RhythmPattern? {
        isCapturing = false
        
        if (taps.size < RhythmPattern.MIN_TAPS) {
            return null
        }
        
        return RhythmPattern(
            taps = taps.toList(),
            totalDuration = System.currentTimeMillis() - startTime
        )
    }
    
    /**
     * Cancel the current capture session.
     */
    @Synchronized
    fun cancel() {
        isCapturing = false
        taps.clear()
        _tapCount.value = 0
    }
    
    /**
     * Check if currently capturing.
     */
    fun isActive(): Boolean = isCapturing
}
```

---

### 1.8 Implement Rhythm Matcher (Fuzzy Matching)

**File**: `blocks/rhythm/src/main/kotlin/.../domain/RhythmMatcher.kt`

```kotlin
/**
 * Fuzzy matcher for rhythm patterns.
 * 
 * SECURITY NOTE: This is safe because matching doesn't derive keys.
 * The rhythm only GATES access to hardware-backed Keystore keys.
 * Tolerance here improves UX without compromising security.
 */
class RhythmMatcher(
    private val timingTolerance: Float = 0.25f,      // 25% timing tolerance
    private val positionWeight: Float = 0.15f,        // 15% weight for position
    private val confidenceThreshold: Float = 0.75f    // 75% required to match
) {
    
    /**
     * Match two patterns and return detailed result.
     */
    fun match(stored: RhythmPattern, attempt: RhythmPattern): MatchResult {
        // Must have same number of taps
        if (stored.taps.size != attempt.taps.size) {
            return MatchResult(
                confidence = 0f,
                isMatch = false,
                details = MatchDetails.TapCountMismatch(
                    expected = stored.taps.size,
                    actual = attempt.taps.size
                )
            )
        }
        
        // Compare intervals (most important - 85% weight)
        val intervalScore = compareIntervals(stored.intervals, attempt.intervals)
        
        // Compare positions (secondary - 15% weight)
        val positionScore = comparePositions(stored.taps, attempt.taps)
        
        // Weighted confidence
        val confidence = intervalScore * (1f - positionWeight) + positionScore * positionWeight
        
        return MatchResult(
            confidence = confidence,
            isMatch = confidence >= confidenceThreshold,
            details = MatchDetails.Scored(
                intervalScore = intervalScore,
                positionScore = positionScore
            )
        )
    }
    
    private fun compareIntervals(stored: List<Long>, attempt: List<Long>): Float {
        if (stored.isEmpty()) return 1f
        
        var totalScore = 0f
        
        stored.zip(attempt).forEach { (expected, actual) ->
            val tolerance = expected * timingTolerance
            val diff = kotlin.math.abs(expected - actual).toFloat()
            
            val score = when {
                diff <= tolerance * 0.5f -> 1.0f      // Perfect zone
                diff <= tolerance -> 0.85f             // Good zone
                diff <= tolerance * 1.5f -> 0.5f       // Acceptable zone
                diff <= tolerance * 2f -> 0.25f        // Poor zone
                else -> 0f                              // Fail zone
            }
            
            totalScore += score
        }
        
        return totalScore / stored.size
    }
    
    private fun comparePositions(stored: List<RhythmTap>, attempt: List<RhythmTap>): Float {
        var matches = 0
        
        stored.zip(attempt).forEach { (s, a) ->
            // Check if in same zone (3x3 grid)
            val sZone = getZone(s.x, s.y)
            val aZone = getZone(a.x, a.y)
            
            if (sZone == aZone) matches++
        }
        
        return matches.toFloat() / stored.size
    }
    
    private fun getZone(x: Float, y: Float): Int {
        val col = (x * 3).toInt().coerceIn(0, 2)
        val row = (y * 3).toInt().coerceIn(0, 2)
        return row * 3 + col // 0-8
    }
}

data class MatchResult(
    val confidence: Float,
    val isMatch: Boolean,
    val details: MatchDetails
)

sealed class MatchDetails {
    data class TapCountMismatch(val expected: Int, val actual: Int) : MatchDetails()
    data class Scored(val intervalScore: Float, val positionScore: Float) : MatchDetails()
}
```

---

### 1.9 Implement Rhythm Quantizer

**File**: `blocks/rhythm/src/main/kotlin/.../domain/RhythmQuantizer.kt`

```kotlin
/**
 * Quantizes rhythm patterns to canonical form for storage.
 * This reduces sensitivity while maintaining pattern identity.
 */
object RhythmQuantizer {
    
    private const val TIME_QUANTUM_MS = 50L    // Round to nearest 50ms
    private const val PRESSURE_LEVELS = 10      // 10 discrete pressure levels
    private const val POSITION_GRID = 5         // 5x5 position grid
    
    /**
     * Quantize a rhythm pattern for storage.
     */
    fun quantize(pattern: RhythmPattern): RhythmPattern {
        val quantizedTaps = pattern.taps.map { tap ->
            tap.copy(
                // Quantize timestamp to nearest TIME_QUANTUM_MS
                timestamp = (tap.timestamp / TIME_QUANTUM_MS) * TIME_QUANTUM_MS,
                // Quantize pressure to discrete levels
                pressure = (tap.pressure * PRESSURE_LEVELS).roundToInt().toFloat() / PRESSURE_LEVELS,
                // Quantize position to grid
                x = (tap.x * POSITION_GRID).roundToInt().toFloat() / POSITION_GRID,
                y = (tap.y * POSITION_GRID).roundToInt().toFloat() / POSITION_GRID,
                // Quantize duration
                duration = (tap.duration / TIME_QUANTUM_MS) * TIME_QUANTUM_MS
            )
        }
        
        return pattern.copy(taps = quantizedTaps)
    }
}
```

---

### 1.10 Implement Rhythm Security Manager (Core Security Logic)

**File**: `blocks/rhythm/src/main/kotlin/.../security/RhythmSecurityManager.kt`

```kotlin
/**
 * Core security manager for rhythm-based authentication.
 * 
 * ARCHITECTURE:
 * - Rhythm is a GATEKEEPER, not a key derivation source
 * - Master keys are stored in Android Keystore (hardware-backed)
 * - Rhythm unlocks access to those keys via fuzzy matching
 * - This provides BOTH tolerance AND security
 */
class RhythmSecurityManager(
    private val keystoreManager: KeystoreManager,
    private val matcher: RhythmMatcher,
    private val storage: SecureStorage,
    private val crypto: CryptoProvider
) {
    
    companion object {
        // Keystore aliases
        const val REAL_KEY_ALIAS = "void_master_real"
        const val DECOY_KEY_ALIAS = "void_master_decoy"
        
        // Storage keys
        private const val KEY_REAL_TEMPLATE = "rhythm.template.real.encrypted"
        private const val KEY_DECOY_TEMPLATE = "rhythm.template.decoy.encrypted"
        private const val KEY_IDENTITY_SEED = "identity.seed.encrypted"
        
        // Security settings
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 300_000L // 5 minutes
    }
    
    private var failedAttempts = 0
    private var lockoutUntil: Long = 0
    
    // ═══════════════════════════════════════════════════════════════
    // Registration
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Register the primary (real) rhythm.
     * Creates master key and generates recovery phrase.
     */
    suspend fun registerRealRhythm(pattern: RhythmPattern): RegistrationResult {
        return registerRhythm(
            pattern = pattern,
            keyAlias = REAL_KEY_ALIAS,
            templateKey = KEY_REAL_TEMPLATE,
            isDecoy = false
        )
    }
    
    /**
     * Register a decoy rhythm (optional, for plausible deniability).
     */
    suspend fun registerDecoyRhythm(pattern: RhythmPattern): RegistrationResult {
        // Decoy uses a separate key - no recovery phrase needed
        return registerRhythm(
            pattern = pattern,
            keyAlias = DECOY_KEY_ALIAS,
            templateKey = KEY_DECOY_TEMPLATE,
            isDecoy = true
        )
    }
    
    private suspend fun registerRhythm(
        pattern: RhythmPattern,
        keyAlias: String,
        templateKey: String,
        isDecoy: Boolean
    ): RegistrationResult {
        try {
            // Validate pattern
            if (!pattern.isValid) {
                return RegistrationResult.Error("Invalid rhythm pattern")
            }
            
            // 1. Quantize the pattern
            val quantized = RhythmQuantizer.quantize(pattern)
            
            // 2. Generate master key in Keystore (hardware-backed!)
            keystoreManager.generateKey(
                alias = keyAlias,
                requireAuth = false, // Rhythm IS the auth
                useStrongBox = true  // Use secure element if available
            )
            
            // 3. Serialize and encrypt rhythm template
            val templateBytes = RhythmSerializer.serialize(quantized)
            val encryptedTemplate = keystoreManager.encrypt(keyAlias, templateBytes)
            
            // 4. Store encrypted template
            storage.put(templateKey, encryptedTemplate)
            
            // 5. For real rhythm only: generate identity seed and recovery phrase
            val recoveryPhrase = if (!isDecoy) {
                // Generate random seed for identity
                val identitySeed = crypto.generateRandomBytes(16) // 128 bits
                
                // Encrypt seed with master key
                val encryptedSeed = keystoreManager.encrypt(keyAlias, identitySeed)
                storage.put(KEY_IDENTITY_SEED, encryptedSeed)
                
                // Generate BIP-39 phrase from seed
                BIP39.toMnemonic(identitySeed)
            } else {
                emptyList()
            }
            
            return RegistrationResult.Success(
                recoveryPhrase = recoveryPhrase
            )
            
        } catch (e: Exception) {
            return RegistrationResult.Error(e.message ?: "Registration failed")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Unlock
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Attempt to unlock with a rhythm pattern.
     * Checks both real and decoy patterns (constant time).
     */
    suspend fun unlock(attempt: RhythmPattern): UnlockResult {
        // Check lockout
        if (System.currentTimeMillis() < lockoutUntil) {
            val remaining = ((lockoutUntil - System.currentTimeMillis()) / 1000).toInt()
            return UnlockResult.LockedOut(remainingSeconds = remaining)
        }
        
        val quantizedAttempt = RhythmQuantizer.quantize(attempt)
        
        // CRITICAL: Check both in constant time to prevent timing attacks
        val realResult = checkTemplate(quantizedAttempt, KEY_REAL_TEMPLATE, REAL_KEY_ALIAS)
        val decoyResult = checkTemplate(quantizedAttempt, KEY_DECOY_TEMPLATE, DECOY_KEY_ALIAS)
        
        // Artificial delay to ensure constant time
        delay(50)
        
        return when {
            realResult is TemplateCheckResult.Match -> {
                failedAttempts = 0
                UnlockResult.Success(
                    mode = UnlockMode.REAL,
                    identitySeed = getIdentitySeed(REAL_KEY_ALIAS)
                )
            }
            decoyResult is TemplateCheckResult.Match -> {
                failedAttempts = 0
                UnlockResult.Success(
                    mode = UnlockMode.DECOY,
                    identitySeed = null // Decoy has no real identity
                )
            }
            else -> {
                failedAttempts++
                if (failedAttempts >= MAX_ATTEMPTS) {
                    lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                    failedAttempts = 0
                    UnlockResult.LockedOut(
                        remainingSeconds = (LOCKOUT_DURATION_MS / 1000).toInt()
                    )
                } else {
                    UnlockResult.Failed(
                        attemptsRemaining = MAX_ATTEMPTS - failedAttempts
                    )
                }
            }
        }
    }
    
    private suspend fun checkTemplate(
        attempt: RhythmPattern,
        templateKey: String,
        keyAlias: String
    ): TemplateCheckResult {
        val encryptedTemplate = storage.get(templateKey)
            ?: return TemplateCheckResult.NoTemplate
        
        return try {
            // Decrypt template using Keystore
            val templateBytes = keystoreManager.decrypt(keyAlias, encryptedTemplate)
            val storedPattern = RhythmSerializer.deserialize(templateBytes)
            
            // Fuzzy match
            val matchResult = matcher.match(storedPattern, attempt)
            
            if (matchResult.isMatch) {
                TemplateCheckResult.Match(matchResult.confidence)
            } else {
                TemplateCheckResult.NoMatch(matchResult.confidence)
            }
        } catch (e: Exception) {
            TemplateCheckResult.Error(e)
        }
    }
    
    private suspend fun getIdentitySeed(keyAlias: String): ByteArray? {
        val encryptedSeed = storage.get(KEY_IDENTITY_SEED) ?: return null
        return try {
            keystoreManager.decrypt(keyAlias, encryptedSeed)
        } catch (e: Exception) {
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Recovery
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Recover from BIP-39 phrase (new device scenario).
     */
    suspend fun recoverFromPhrase(mnemonic: List<String>): RecoveryResult {
        return try {
            // Validate mnemonic
            if (!BIP39.validate(mnemonic)) {
                return RecoveryResult.InvalidPhrase
            }
            
            // Derive seed from mnemonic
            val identitySeed = BIP39.toEntropy(mnemonic)
            
            // Generate new master key
            keystoreManager.deleteKey(REAL_KEY_ALIAS) // Remove old if exists
            keystoreManager.generateKey(
                alias = REAL_KEY_ALIAS,
                useStrongBox = true
            )
            
            // Store encrypted seed
            val encryptedSeed = keystoreManager.encrypt(REAL_KEY_ALIAS, identitySeed)
            storage.put(KEY_IDENTITY_SEED, encryptedSeed)
            
            // User needs to set up new rhythm
            RecoveryResult.Success(needsNewRhythm = true)
            
        } catch (e: Exception) {
            RecoveryResult.Error(e.message ?: "Recovery failed")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // State Queries
    // ═══════════════════════════════════════════════════════════════
    
    suspend fun hasRealRhythm(): Boolean = storage.contains(KEY_REAL_TEMPLATE)
    suspend fun hasDecoyRhythm(): Boolean = storage.contains(KEY_DECOY_TEMPLATE)
    suspend fun isSetupComplete(): Boolean = hasRealRhythm()
    
    // ═══════════════════════════════════════════════════════════════
    // Panic Wipe
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Emergency wipe of all rhythm data and keys.
     */
    suspend fun panicWipe() {
        keystoreManager.deleteAllVoidKeys()
        storage.clear()
        failedAttempts = 0
        lockoutUntil = 0
    }
}

// Result types
sealed class RegistrationResult {
    data class Success(val recoveryPhrase: List<String>) : RegistrationResult()
    data class Error(val message: String) : RegistrationResult()
}

sealed class UnlockResult {
    data class Success(
        val mode: UnlockMode,
        val identitySeed: ByteArray?
    ) : UnlockResult()
    
    data class Failed(val attemptsRemaining: Int) : UnlockResult()
    data class LockedOut(val remainingSeconds: Int) : UnlockResult()
}

enum class UnlockMode {
    REAL,
    DECOY
}

sealed class TemplateCheckResult {
    data class Match(val confidence: Float) : TemplateCheckResult()
    data class NoMatch(val confidence: Float) : TemplateCheckResult()
    object NoTemplate : TemplateCheckResult()
    data class Error(val exception: Exception) : TemplateCheckResult()
}

sealed class RecoveryResult {
    data class Success(val needsNewRhythm: Boolean) : RecoveryResult()
    object InvalidPhrase : RecoveryResult()
    data class Error(val message: String) : RecoveryResult()
}
```

---

### 1.11 Implement BIP-39 Utility

**File**: `blocks/rhythm/src/main/kotlin/.../security/BIP39.kt`

```kotlin
/**
 * BIP-39 mnemonic phrase generation and validation.
 * Used for recovery phrase functionality.
 */
object BIP39 {
    
    private val wordList: List<String> by lazy {
        // Load BIP-39 English wordlist (2048 words)
        WordDictionary.words
    }
    
    /**
     * Generate mnemonic phrase from entropy.
     * 
     * @param entropy 16 bytes (128 bits) for 12 words, 32 bytes for 24 words
     */
    fun toMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size == 16 || entropy.size == 32) {
            "Entropy must be 16 or 32 bytes"
        }
        
        // Calculate checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = entropy.size / 4 // 4 bits for 16 bytes, 8 for 32
        
        // Combine entropy + checksum bits
        val combined = entropy.toBitString() + hash.toBitString().take(checksumBits)
        
        // Split into 11-bit groups
        val wordCount = combined.length / 11
        val words = mutableListOf<String>()
        
        for (i in 0 until wordCount) {
            val bits = combined.substring(i * 11, (i + 1) * 11)
            val index = bits.toInt(2)
            words.add(wordList[index])
        }
        
        return words
    }
    
    /**
     * Convert mnemonic back to entropy.
     */
    fun toEntropy(mnemonic: List<String>): ByteArray {
        require(mnemonic.size == 12 || mnemonic.size == 24) {
            "Mnemonic must be 12 or 24 words"
        }
        
        // Convert words to bit string
        val bits = mnemonic.joinToString("") { word ->
            val index = wordList.indexOf(word.lowercase())
            require(index >= 0) { "Invalid word: $word" }
            index.toString(2).padStart(11, '0')
        }
        
        // Extract entropy (minus checksum bits)
        val entropyBits = if (mnemonic.size == 12) 128 else 256
        val entropyString = bits.take(entropyBits)
        
        // Convert to bytes
        return entropyString.chunked(8)
            .map { it.toInt(2).toByte() }
            .toByteArray()
    }
    
    /**
     * Validate a mnemonic phrase.
     */
    fun validate(mnemonic: List<String>): Boolean {
        if (mnemonic.size != 12 && mnemonic.size != 24) return false
        
        // Check all words are valid
        if (!mnemonic.all { wordList.contains(it.lowercase()) }) return false
        
        // Verify checksum
        return try {
            val entropy = toEntropy(mnemonic)
            val regenerated = toMnemonic(entropy)
            regenerated == mnemonic.map { it.lowercase() }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun ByteArray.toBitString(): String {
        return joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(2).padStart(8, '0')
        }
    }
}
```

---

### 1.12 Implement Rhythm UI Components

**File**: `blocks/rhythm/src/main/kotlin/.../ui/components/RhythmPad.kt`

```kotlin
/**
 * Large tap area for rhythm input.
 * No visual feedback to prevent shoulder surfing.
 */
@Composable
fun RhythmPad(
    onTap: (pressure: Float, x: Float, y: Float, duration: Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var tapStartTime by remember { mutableLongStateOf(0L) }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                tapStartTime = System.currentTimeMillis()
                                
                                // Normalize position
                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                
                                // Wait for release
                                val released = tryAwaitRelease()
                                
                                if (released) {
                                    val duration = System.currentTimeMillis() - tapStartTime
                                    // Pressure not available in Compose - use 1.0
                                    onTap(1f, x, y, duration)
                                }
                            }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Subtle prompt - no feedback on taps!
        Text(
            text = "Tap your rhythm",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}
```

**File**: `blocks/rhythm/src/main/kotlin/.../ui/components/TapIndicator.kt`

```kotlin
/**
 * Shows tap count progress as dots.
 */
@Composable
fun TapIndicator(
    tapCount: Int,
    minTaps: Int = 4,
    maxTaps: Int = 12,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(maxTaps) { index ->
            val isFilled = index < tapCount
            val isRequired = index < minTaps
            
            Box(
                modifier = Modifier
                    .size(if (isRequired) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isFilled -> MaterialTheme.colorScheme.primary
                            isRequired -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}
```

---

### 1.13 Implement Rhythm Screens

**File**: `blocks/rhythm/src/main/kotlin/.../ui/RhythmSetupScreen.kt`

```kotlin
@Composable
fun RhythmSetupScreen(
    viewModel: RhythmSetupViewModel = koinViewModel(),
    onComplete: (RhythmPattern) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val capture = remember { RhythmCapture() }
    val tapCount by capture.tapCount.collectAsState()
    
    LaunchedEffect(Unit) {
        capture.start()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "Create Your Rhythm Key",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Tap a memorable pattern — like a song beat, morse code, or a personal sequence.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        TapIndicator(
            tapCount = tapCount,
            minTaps = RhythmPattern.MIN_TAPS
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        RhythmPad(
            onTap = { pressure, x, y, duration ->
                capture.recordTap(pressure, x, y, duration)
            }
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = {
                    capture.cancel()
                    capture.start()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Over")
            }
            
            Button(
                onClick = {
                    capture.finish()?.let { pattern ->
                        onComplete(pattern)
                    }
                },
                enabled = tapCount >= RhythmPattern.MIN_TAPS,
                modifier = Modifier.weight(1f)
            ) {
                Text("Continue")
            }
        }
    }
}
```

**File**: `blocks/rhythm/src/main/kotlin/.../ui/RhythmConfirmScreen.kt`

```kotlin
@Composable
fun RhythmConfirmScreen(
    originalPattern: RhythmPattern,
    viewModel: RhythmConfirmViewModel = koinViewModel(),
    onConfirmed: () -> Unit,
    onRetry: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val capture = remember { RhythmCapture() }
    val tapCount by capture.tapCount.collectAsState()
    val matcher = remember { RhythmMatcher() }
    
    var matchResult by remember { mutableStateOf<MatchResult?>(null) }
    
    LaunchedEffect(Unit) {
        capture.start()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "Confirm Your Rhythm",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Repeat the same pattern to confirm.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Show match result if available
        matchResult?.let { result ->
            MatchFeedback(result = result)
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        TapIndicator(
            tapCount = tapCount,
            minTaps = originalPattern.taps.size,
            maxTaps = originalPattern.taps.size
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        RhythmPad(
            onTap = { pressure, x, y, duration ->
                capture.recordTap(pressure, x, y, duration)
                
                // Auto-check when tap count matches
                if (capture.tapCount.value == originalPattern.taps.size) {
                    capture.finish()?.let { attempt ->
                        val result = matcher.match(originalPattern, attempt)
                        matchResult = result
                        
                        if (result.isMatch) {
                            viewModel.onConfirmed(originalPattern)
                        }
                    }
                }
            },
            enabled = matchResult?.isMatch != true
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        when {
            matchResult?.isMatch == true -> {
                Button(
                    onClick = onConfirmed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue")
                }
            }
            matchResult != null -> {
                Column {
                    Text(
                        text = "Pattern didn't match. Try again!",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start Over")
                        }
                        Button(
                            onClick = {
                                matchResult = null
                                capture.cancel()
                                capture.start()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchFeedback(result: MatchResult) {
    val (icon, color, text) = when {
        result.isMatch -> Triple(
            Icons.Default.Check,
            MaterialTheme.colorScheme.primary,
            "Perfect match!"
        )
        result.confidence > 0.5f -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "Close! Try to match the timing more precisely."
        )
        else -> Triple(
            Icons.Default.Close,
            MaterialTheme.colorScheme.error,
            "Pattern didn't match. Try again."
        )
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = color)
        Text(text, color = color)
    }
}
```

**File**: `blocks/rhythm/src/main/kotlin/.../ui/RhythmUnlockScreen.kt`

```kotlin
@Composable
fun RhythmUnlockScreen(
    viewModel: RhythmUnlockViewModel = koinViewModel(),
    onUnlocked: (UnlockMode) -> Unit,
    onForgot: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val capture = remember { RhythmCapture() }
    val tapCount by capture.tapCount.collectAsState()
    
    LaunchedEffect(state) {
        when (val s = state) {
            is RhythmUnlockState.Success -> onUnlocked(s.mode)
            else -> {}
        }
    }
    
    LaunchedEffect(Unit) {
        capture.start()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // VOID logo or icon
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Status message
            when (val s = state) {
                is RhythmUnlockState.Idle -> {
                    Text(
                        text = "Tap to unlock",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                is RhythmUnlockState.Failed -> {
                    Text(
                        text = "${s.attemptsRemaining} attempts remaining",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is RhythmUnlockState.LockedOut -> {
                    Text(
                        text = "Locked for ${s.remainingSeconds}s",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Rhythm pad
            RhythmPad(
                onTap = { pressure, x, y, duration ->
                    capture.recordTap(pressure, x, y, duration)
                },
                enabled = state !is RhythmUnlockState.LockedOut,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Done button
            Button(
                onClick = {
                    capture.finish()?.let { pattern ->
                        viewModel.attemptUnlock(pattern)
                        capture.cancel()
                        capture.start()
                    }
                },
                enabled = tapCount >= RhythmPattern.MIN_TAPS &&
                         state !is RhythmUnlockState.LockedOut
            ) {
                Text("Unlock")
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Forgot link
            TextButton(onClick = onForgot) {
                Text("Forgot your rhythm?")
            }
        }
    }
}
```

---

### 1.14 Implement Rhythm ViewModels

**File**: `blocks/rhythm/src/main/kotlin/.../ui/RhythmUnlockViewModel.kt`

```kotlin
class RhythmUnlockViewModel(
    private val securityManager: RhythmSecurityManager
) : ViewModel() {
    
    private val _state = MutableStateFlow<RhythmUnlockState>(RhythmUnlockState.Idle)
    val state: StateFlow<RhythmUnlockState> = _state.asStateFlow()
    
    fun attemptUnlock(pattern: RhythmPattern) {
        viewModelScope.launch {
            _state.value = RhythmUnlockState.Checking
            
            when (val result = securityManager.unlock(pattern)) {
                is UnlockResult.Success -> {
                    _state.value = RhythmUnlockState.Success(result.mode)
                }
                is UnlockResult.Failed -> {
                    _state.value = RhythmUnlockState.Failed(result.attemptsRemaining)
                }
                is UnlockResult.LockedOut -> {
                    _state.value = RhythmUnlockState.LockedOut(result.remainingSeconds)
                    startLockoutCountdown(result.remainingSeconds)
                }
            }
        }
    }
    
    private fun startLockoutCountdown(seconds: Int) {
        viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000)
                remaining--
                _state.value = RhythmUnlockState.LockedOut(remaining)
            }
            _state.value = RhythmUnlockState.Idle
        }
    }
}

sealed class RhythmUnlockState {
    object Idle : RhythmUnlockState()
    object Checking : RhythmUnlockState()
    data class Success(val mode: UnlockMode) : RhythmUnlockState()
    data class Failed(val attemptsRemaining: Int) : RhythmUnlockState()
    data class LockedOut(val remainingSeconds: Int) : RhythmUnlockState()
}
```

---

### 1.15 Implement Rhythm Events

**File**: `blocks/rhythm/src/main/kotlin/.../events/RhythmEvents.kt`

```kotlin
sealed class RhythmEvent : Event {
    
    data class RhythmRegistered(
        val isDecoy: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : RhythmEvent()
    
    data class RhythmVerified(
        val mode: UnlockMode,
        override val timestamp: Long = System.currentTimeMillis()
    ) : RhythmEvent()
    
    data class RhythmFailed(
        val attemptsRemaining: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : RhythmEvent()
    
    data class RhythmLocked(
        val lockedUntil: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : RhythmEvent()
    
    data class PanicWipeTriggered(
        override val timestamp: Long = System.currentTimeMillis()
    ) : RhythmEvent()
}
```

---

### 1.16 Complete Rhythm Block Manifest

**File**: `blocks/rhythm/src/main/kotlin/.../RhythmBlock.kt`

```kotlin
@Block(id = "rhythm", enabledByDefault = true)
class RhythmBlock : BlockManifest {
    
    override val id: String = "rhythm"
    
    override val routes: List<Route> = listOf(
        Route.Screen(Routes.Rhythm.SETUP, "Set Up Rhythm"),
        Route.Screen(Routes.Rhythm.CONFIRM, "Confirm Rhythm"),
        Route.Screen(Routes.Rhythm.UNLOCK, "Unlock"),
        Route.Screen(Routes.Rhythm.RECOVERY, "Recovery"),
        Route.Screen(Routes.Rhythm.DECOY_SETUP, "Set Up Decoy"),
    )
    
    override val events = BlockEvents(
        emits = listOf(
            RhythmEvent.RhythmRegistered::class,
            RhythmEvent.RhythmVerified::class,
            RhythmEvent.RhythmFailed::class,
            RhythmEvent.RhythmLocked::class,
            RhythmEvent.PanicWipeTriggered::class,
        ),
        observes = emptyList()
    )
    
    override fun Module.install() {
        // Domain
        single { RhythmMatcher() }
        single { RhythmCapture() }
        
        // Security
        single { 
            RhythmSecurityManager(
                keystoreManager = get(),
                matcher = get(),
                storage = get(),
                crypto = get()
            )
        }
        
        // ViewModels
        viewModel { RhythmSetupViewModel(get()) }
        viewModel { RhythmConfirmViewModel(get()) }
        viewModel { RhythmUnlockViewModel(get()) }
        viewModel { RhythmRecoveryViewModel(get()) }
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        composable(Routes.Rhythm.SETUP) {
            RhythmSetupScreen(
                onComplete = { pattern ->
                    // Store pattern temporarily for confirmation
                    navigator.navigate(Routes.Rhythm.CONFIRM, pattern)
                }
            )
        }
        
        composable(Routes.Rhythm.CONFIRM) { backStackEntry ->
            val pattern = backStackEntry.arguments?.getParcelable<RhythmPattern>("pattern")
            if (pattern != null) {
                RhythmConfirmScreen(
                    originalPattern = pattern,
                    onConfirmed = { navigator.navigate(Routes.Onboarding.RECOVERY_PHRASE) },
                    onRetry = { navigator.goBack() }
                )
            }
        }
        
        composable(Routes.Rhythm.UNLOCK) {
            RhythmUnlockScreen(
                onUnlocked = { mode ->
                    navigator.navigateAndClear(Routes.Messages.LIST)
                },
                onForgot = { navigator.navigate(Routes.Rhythm.RECOVERY) }
            )
        }
        
        composable(Routes.Rhythm.RECOVERY) {
            RhythmRecoveryScreen(
                onRecovered = { navigator.navigate(Routes.Rhythm.SETUP) },
                onCancel = { navigator.goBack() }
            )
        }
    }
}
```

---

## Phase 1B Tests

**File**: `blocks/rhythm/src/test/kotlin/.../RhythmSecurityManagerTest.kt`

```kotlin
class RhythmSecurityManagerTest {
    
    private lateinit var securityManager: RhythmSecurityManager
    private lateinit var keystoreManager: FakeKeystoreManager
    private lateinit var matcher: RhythmMatcher
    private lateinit var storage: FakeSecureStorage
    private lateinit var crypto: FakeCryptoProvider
    
    @BeforeEach
    fun setup() {
        keystoreManager = FakeKeystoreManager()
        matcher = RhythmMatcher()
        storage = FakeSecureStorage()
        crypto = FakeCryptoProvider()
        
        securityManager = RhythmSecurityManager(
            keystoreManager = keystoreManager,
            matcher = matcher,
            storage = storage,
            crypto = crypto
        )
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Registration Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `registration creates keystore key`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        
        val result = securityManager.registerRealRhythm(pattern)
        
        assertThat(result).isInstanceOf(RegistrationResult.Success::class.java)
        assertThat(keystoreManager.hasKey(RhythmSecurityManager.REAL_KEY_ALIAS)).isTrue()
    }
    
    @Test
    fun `registration returns 12-word recovery phrase`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        
        val result = securityManager.registerRealRhythm(pattern)
        
        assertThat(result).isInstanceOf(RegistrationResult.Success::class.java)
        val phrase = (result as RegistrationResult.Success).recoveryPhrase
        assertThat(phrase).hasSize(12)
    }
    
    @Test
    fun `registration stores encrypted template`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        
        securityManager.registerRealRhythm(pattern)
        
        assertThat(storage.contains("rhythm.template.real.encrypted")).isTrue()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Unlock Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `unlock succeeds with matching rhythm`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        securityManager.registerRealRhythm(pattern)
        
        val result = securityManager.unlock(pattern)
        
        assertThat(result).isInstanceOf(UnlockResult.Success::class.java)
        assertThat((result as UnlockResult.Success).mode).isEqualTo(UnlockMode.REAL)
    }
    
    @Test
    fun `unlock succeeds with slightly different rhythm (tolerance)`() = runTest {
        val stored = createTestPattern(listOf(200L, 300L, 200L, 300L))
        securityManager.registerRealRhythm(stored)
        
        // Within 25% tolerance
        val attempt = createTestPattern(listOf(210L, 285L, 205L, 295L))
        val result = securityManager.unlock(attempt)
        
        assertThat(result).isInstanceOf(UnlockResult.Success::class.java)
    }
    
    @Test
    fun `unlock fails with very different rhythm`() = runTest {
        val stored = createTestPattern(listOf(200L, 300L, 200L, 300L))
        securityManager.registerRealRhythm(stored)
        
        val attempt = createTestPattern(listOf(500L, 100L, 500L, 100L))
        val result = securityManager.unlock(attempt)
        
        assertThat(result).isInstanceOf(UnlockResult.Failed::class.java)
    }
    
    @Test
    fun `unlock returns identity seed on success`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        securityManager.registerRealRhythm(pattern)
        
        val result = securityManager.unlock(pattern)
        
        assertThat((result as UnlockResult.Success).identitySeed).isNotNull()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Decoy Mode Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `decoy rhythm unlocks decoy mode`() = runTest {
        val realPattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        val decoyPattern = createTestPattern(listOf(100L, 100L, 100L, 100L))
        
        securityManager.registerRealRhythm(realPattern)
        securityManager.registerDecoyRhythm(decoyPattern)
        
        // Real rhythm → real mode
        val realResult = securityManager.unlock(realPattern)
        assertThat((realResult as UnlockResult.Success).mode).isEqualTo(UnlockMode.REAL)
        
        // Decoy rhythm → decoy mode
        val decoyResult = securityManager.unlock(decoyPattern)
        assertThat((decoyResult as UnlockResult.Success).mode).isEqualTo(UnlockMode.DECOY)
    }
    
    @Test
    fun `decoy mode has no identity seed`() = runTest {
        val realPattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        val decoyPattern = createTestPattern(listOf(100L, 100L, 100L, 100L))
        
        securityManager.registerRealRhythm(realPattern)
        securityManager.registerDecoyRhythm(decoyPattern)
        
        val decoyResult = securityManager.unlock(decoyPattern) as UnlockResult.Success
        assertThat(decoyResult.identitySeed).isNull()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Lockout Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `lockout after max failed attempts`() = runTest {
        val stored = createTestPattern(listOf(200L, 300L, 200L, 300L))
        securityManager.registerRealRhythm(stored)
        
        val wrong = createTestPattern(listOf(500L, 500L, 500L, 500L))
        
        repeat(5) {
            securityManager.unlock(wrong)
        }
        
        val result = securityManager.unlock(wrong)
        assertThat(result).isInstanceOf(UnlockResult.LockedOut::class.java)
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Recovery Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `recovery from valid phrase succeeds`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        val regResult = securityManager.registerRealRhythm(pattern)
        val phrase = (regResult as RegistrationResult.Success).recoveryPhrase
        
        // Simulate device wipe
        keystoreManager.deleteAllVoidKeys()
        storage.clear()
        
        val result = securityManager.recoverFromPhrase(phrase)
        
        assertThat(result).isInstanceOf(RecoveryResult.Success::class.java)
        assertThat((result as RecoveryResult.Success).needsNewRhythm).isTrue()
    }
    
    @Test
    fun `recovery from invalid phrase fails`() = runTest {
        val invalidPhrase = listOf("invalid", "words", "here")
        
        val result = securityManager.recoverFromPhrase(invalidPhrase)
        
        assertThat(result).isEqualTo(RecoveryResult.InvalidPhrase)
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Panic Wipe Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `panic wipe clears all data`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        securityManager.registerRealRhythm(pattern)
        
        securityManager.panicWipe()
        
        assertThat(securityManager.hasRealRhythm()).isFalse()
        assertThat(keystoreManager.hasKey(RhythmSecurityManager.REAL_KEY_ALIAS)).isFalse()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Security Property Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `master key is stored in keystore not in storage`() = runTest {
        val pattern = createTestPattern(listOf(200L, 300L, 200L, 300L))
        securityManager.registerRealRhythm(pattern)
        
        // Key should be in Keystore
        assertThat(keystoreManager.hasKey(RhythmSecurityManager.REAL_KEY_ALIAS)).isTrue()
        
        // Raw key bytes should not be in regular storage
        val allKeys = storage.getAllKeys()
        allKeys.forEach { key ->
            val value = storage.get(key)
            // Values are encrypted, not raw keys
            assertThat(value?.size).isNotEqualTo(32) // Not a raw 256-bit key
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Helper Functions
    // ═══════════════════════════════════════════════════════════════
    
    private fun createTestPattern(intervals: List<Long>): RhythmPattern {
        var time = 0L
        val taps = mutableListOf<RhythmTap>()
        
        intervals.forEachIndexed { index, interval ->
            if (index > 0) time += intervals[index - 1]
            taps.add(RhythmTap(
                timestamp = time,
                pressure = 1f,
                x = 0.5f,
                y = 0.5f,
                duration = 100L
            ))
        }
        
        return RhythmPattern(
            taps = taps,
            totalDuration = time + intervals.last()
        )
    }
}
```

---

## Phase 1B Completion Checklist

Run tests:
```bash
./gradlew :blocks:rhythm:test
```

Verify:
- [ ] RhythmCapture captures taps correctly
- [ ] RhythmMatcher accepts similar patterns (25% tolerance)
- [ ] RhythmMatcher rejects different patterns
- [ ] RhythmSecurityManager stores keys in Keystore
- [ ] RhythmSecurityManager template is encrypted
- [ ] Registration returns 12-word recovery phrase
- [ ] Unlock succeeds with matching rhythm
- [ ] Unlock fails with different rhythm
- [ ] Decoy rhythm unlocks decoy mode
- [ ] Lockout activates after 5 failures
- [ ] Recovery from phrase works
- [ ] Panic wipe clears all data
- [ ] UI components render without crashes

---

# PHASE 1C: Recovery System & Onboarding

## Prerequisites
- Phase 1A complete
- Phase 1B complete
- Rhythm block fully functional

## Tasks

### 1.17 Implement Recovery Phrase Display Screen

**File**: `blocks/onboarding/src/main/kotlin/.../ui/RecoveryPhraseScreen.kt`

```kotlin
@Composable
fun RecoveryPhraseScreen(
    phrase: List<String>,
    onConfirmed: () -> Unit,
    onBack: () -> Unit
) {
    var hasAcknowledged by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Your Recovery Phrase",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Warning card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Warning,
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Write these words down and store them safely. " +
                           "This is the ONLY way to recover your account if you forget your rhythm.",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Phrase grid
        PhraseGrid(phrase = phrase)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Acknowledgment checkbox
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { hasAcknowledged = !hasAcknowledged }
        ) {
            Checkbox(
                checked = hasAcknowledged,
                onCheckedChange = { hasAcknowledged = it }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("I have written down my recovery phrase")
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onConfirmed,
            enabled = hasAcknowledged,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun PhraseGrid(phrase: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        phrase.chunked(3).forEachIndexed { rowIndex, rowWords ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                rowWords.forEachIndexed { colIndex, word ->
                    val number = rowIndex * 3 + colIndex + 1
                    WordChip(number = number, word = word)
                }
            }
            if (rowIndex < 3) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun WordChip(number: Int, word: String) {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace
            )
        )
    }
}
```

---

### 1.18 Complete Onboarding Block

Implement the full flow connecting all pieces. See continuation in subsequent phases.

---

## Phase 1C Completion Checklist

```bash
./gradlew :blocks:onboarding:test
./gradlew :app:connectedAndroidTest
```

---

# Remaining Phases (2-4)

The remaining phases (Messaging, Networking, Hardening, Polish) continue as documented in the previous version. The key difference is that all encryption operations now properly use:

1. **KeystoreManager** for master key storage
2. **RhythmSecurityManager** for authentication
3. **Proper separation** between gating (rhythm) and encryption (keystore)

---

# Testing Commands Summary

```bash
# Run all tests
./gradlew test

# Run specific block tests
./gradlew :blocks:identity:test
./gradlew :blocks:rhythm:test
./gradlew :blocks:messaging:test
./gradlew :blocks:contacts:test

# Run instrumented tests (requires device/emulator)
./gradlew :app:connectedAndroidTest

# Verify architecture rules
./gradlew verifyBlockIsolation

# Build release
./gradlew assembleRelease

# Check APK size
ls -lh app/build/outputs/apk/release/
```

---

# Key Reminders for Claude Code

1. **Rhythm is a GATEKEEPER** — It does NOT derive keys, it GATES access to hardware-backed keys
2. **All master keys in KeystoreManager** — Never store raw keys in SharedPreferences or SQLite
3. **Fuzzy matching is safe now** — Because it doesn't affect cryptographic security
4. **Blocks never import other blocks** — Use EventBus for communication
5. **Test after each section** — Run tests before moving on
6. **Follow Identity block patterns** — It's the reference implementation
7. **Commit after each working section**

---

# Security Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                     VOID SECURITY MODEL                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   USER INPUT                    STORAGE LAYER                    │
│   ──────────                    ─────────────                    │
│   ┌──────────┐                  ┌─────────────────┐              │
│   │  Rhythm  │ ───Matches?───►  │ Android Keystore│              │
│   │  Pattern │                  │ (Hardware HSM)  │              │
│   └──────────┘                  │                 │              │
│        │                        │ • Master Key    │              │
│        │ No                     │ • Decoy Key     │              │
│        ▼                        └────────┬────────┘              │
│   ┌──────────┐                           │                       │
│   │  DENIED  │                     Yes   │                       │
│   │ (Lockout)│                           ▼                       │
│   └──────────┘                  ┌─────────────────┐              │
│                                 │  Decrypt Data   │              │
│                                 │  ─────────────  │              │
│   RECOVERY                      │ • Identity Seed │              │
│   ────────                      │ • Messages      │              │
│   ┌──────────┐                  │ • Contacts      │              │
│   │ BIP-39   │ ───Restore───►   └─────────────────┘              │
│   │ 12 Words │    Seed                                           │
│   └──────────┘                                                   │
│                                                                  │
│   WHY THIS WORKS:                                                │
│   • Rhythm provides fuzzy human-friendly authentication          │
│   • Keystore provides hardware-backed key protection             │
│   • BIP-39 provides reliable offline recovery                    │
│   • Decoy mode uses separate Keystore alias (indistinguishable) │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

**Document Version**: 2.0
**Last Updated**: December 2025
**Architecture**: Slate + Block v1.0
**Security Model**: Rhythm-Gated Keystore


   ## 🔒 Security Principles (Non-Negotiable)

 ### ✅ MUST Have:
 1. **Client-Side Encryption ONLY** - All encryption happens on device
 2. **Server Sees Only Encrypted Blobs** - No plaintext ever transmitted
 3. **Hardware-Backed Keys** - Keys stored in Android Keystore
 4. **Forward Secrecy** - Ephemeral session keys
 5. **Message Authenticity** - HMAC verification
 6. **No Key Derivation from Rhythm** - Rhythm is gatekeeper, not key source

 ### ❌ MUST NOT:
 1. ❌ Send plaintext over network
 2. ❌ Store plaintext on server
 3. ❌ Derive encryption keys from rhythm pattern
 4. ❌ Trust server with any secrets
 5. ❌ Skip MAC verification
 6. ❌ Reuse nonces

 ---

 ## 📋 Implementation Checklist

 ### Phase 1: Key Management ✅
 - [x] KeystoreManager implemented (hardware-backed)
 - [x] CryptoProvider interface defined
 - [x] TinkCryptoProvider implemented
 - [x] MessageEncryption implemented (Signal Protocol simplified)
 - [ ] **TODO**: Verify keys are generated per-contact
 - [ ] **TODO**: Implement contact public key exchange

 ### Phase 2: Message Encryption Flow 🔨
 - [ ] **Step 1**: Get sender's private key from Keystore
 - [ ] **Step 2**: Get recipient's public key from Contacts
 - [ ] **Step 3**: Encrypt message using MessageEncryption
 - [ ] **Step 4**: Serialize encrypted envelope
 - [ ] **Step 5**: Send encrypted blob to server
 - [ ] **Step 6**: Verify server never sees plaintext (logging)

 ### Phase 3: Message Decryption Flow 🔨
 - [ ] **Step 1**: Poll server for encrypted messages
 - [ ] **Step 2**: Deserialize encrypted envelope
 - [ ] **Step 3**: Get sender's public key from Contacts
 - [ ] **Step 4**: Get recipient's private key from Keystore
 - [ ] **Step 5**: Decrypt using MessageEncryption
 - [ ] **Step 6**: Verify MAC before displaying
 - [ ] **Step 7**: Display plaintext in UI

 ### Phase 4: Contact Key Exchange 🔨
 - [ ] Generate X25519 key pair for each identity
 - [ ] Store private key in Android Keystore
 - [ ] Include public key in contact request
 - [ ] Verify contact public key (manual/QR)
 - [ ] Store contact public key securely

 ### Phase 5: Security Verification ✅
 - [ ] Add debug logging for each encryption step
 - [ ] Verify server logs show only base64 encrypted blobs
 - [ ] Verify MAC is checked before decryption
 - [ ] Test message tampering detection
 - [ ] Test replay attack prevention

 ---

 ## 🔐 Complete Secure Message Flow

 ### Send Message (Client A → Client B)

 ```
 ┌─────────────────────────────────────────────────────────────┐
 │ CLIENT A (Sender)                                            │
 ├─────────────────────────────────────────────────────────────┤
 │ 1. User types: "Hello World"                                │
 │    └─> [PLAINTEXT] in memory only                           │
 │                                                              │
 │ 2. Load Keys:                                               │
 │    a) myPrivateKey ← Android Keystore (hardware-backed)     │
 │    b) contactPublicKey ← Contacts DB (encrypted storage)    │
 │    └─> [SECURITY CHECK]: Keys never leave secure storage   │
 │                                                              │
 │ 3. Encrypt Message:                                         │
 │    a) ECDH: sharedSecret = DH(myPrivateKey, contactPubKey)  │
 │    b) HKDF: (encKey, macKey) = derive(sharedSecret)         │
 │    c) AES-GCM: ciphertext = encrypt(plaintext, encKey)      │
 │    d) HMAC: mac = HMAC(ciphertext + nonce, macKey)          │
 │    └─> [SECURITY CHECK]: Plaintext is zeroed after encrypt │
 │                                                              │
 │ 4. Create Envelope:                                         │
 │    envelope = {                                             │
 │      ciphertext: [binary],    ← Encrypted message           │
 │      nonce: [random],          ← One-time random             │
 │      mac: [hmac],              ← Authenticity proof          │
 │      version: 1                ← Protocol version            │
 │    }                                                         │
 │    └─> [SECURITY CHECK]: No metadata in envelope            │
 │                                                              │
 │ 5. Serialize:                                               │
 │    encryptedPayload = base64(JSON.stringify(envelope))      │
 │    └─> [SECURITY CHECK]: Still encrypted                   │
 │                                                              │
 │ 6. Send to Server:                                          │
 │    POST /api/v1/messages/send                               │
 │    {                                                         │
 │      messageId: UUID,                                       │
 │      recipientIdentity: "word1.word2.word3",                │
 │      encryptedPayload: "eyJjaXBoZXJ0ZXh0IjpbLi4u...",       │
 │      timestamp: 1234567890                                  │
 │    }                                                         │
 │    └─> [SECURITY CHECK]: Server gets ONLY encrypted blob   │
 │                                                              │
 │ 7. Store Locally (encrypted):                               │
 │    SecureStorage.put("message.{id}", encryptedMessage)      │
 │    └─> [SECURITY CHECK]: At-rest encryption via Tink       │
 └─────────────────────────────────────────────────────────────┘

 ┌─────────────────────────────────────────────────────────────┐
 │ SERVER (Relay)                                               │
 ├─────────────────────────────────────────────────────────────┤
 │ 1. Receive POST /api/v1/messages/send                       │
 │    └─> Sees: {recipientIdentity, encryptedPayload}          │
 │    └─> [SECURITY GUARANTEE]: No plaintext visible           │
 │                                                              │
 │ 2. Queue Message:                                           │
 │    messageQueue[recipientIdentity].add(encryptedPayload)    │
 │    └─> [SECURITY]: Stores opaque blob only                  │
 │                                                              │
 │ 3. Return Success:                                          │
 │    {success: true}                                          │
 └─────────────────────────────────────────────────────────────┘

 ┌─────────────────────────────────────────────────────────────┐
 │ CLIENT B (Receiver)                                          │
 ├─────────────────────────────────────────────────────────────┤
 │ 1. Poll Server (every 3 seconds):                           │
 │    GET /api/v1/messages/receive?identity=word1.word2.word3  │
 │    └─> Server returns: [encryptedPayload1, ...]             │
 │                                                              │
 │ 2. Receive Encrypted Message:                               │
 │    encryptedPayload = response[0].encryptedPayload          │
 │    └─> [SECURITY CHECK]: Still encrypted from network       │
 │                                                              │
 │ 3. Deserialize:                                             │
 │    envelope = JSON.parse(base64Decode(encryptedPayload))    │
 │    └─> envelope = {ciphertext, nonce, mac, version}         │
 │                                                              │
 │ 4. Load Keys:                                               │
 │    a) myPrivateKey ← Android Keystore                       │
 │    b) senderPublicKey ← Contacts DB                         │
 │    └─> [SECURITY CHECK]: Keys from secure storage          │
 │                                                              │
 │ 5. Decrypt Message:                                         │
 │    a) ECDH: sharedSecret = DH(myPrivateKey, senderPubKey)   │
 │    b) HKDF: (encKey, macKey) = derive(sharedSecret)         │
 │    c) VERIFY MAC: ✓ MAC matches computed HMAC               │
 │       └─> [SECURITY CHECK]: Reject if MAC mismatch          │
 │    d) AES-GCM: plaintext = decrypt(ciphertext, encKey)      │
 │    └─> [SECURITY CHECK]: MAC verified BEFORE decrypt       │
 │                                                              │
 │ 6. Display Message:                                         │
 │    UI shows: "Hello World"                                  │
 │    └─> [PLAINTEXT] only in memory, only after verification │
 │                                                              │
 │ 7. Store Locally (encrypted):                               │
 │    SecureStorage.put("message.{id}", encryptedMessage)      │
 │    └─> [SECURITY CHECK]: At-rest encryption                │
 └─────────────────────────────────────────────────────────────┘
 ```

 ---

 ## 📝 Security Logging Points

 ### Log Level: DEBUG (removed in production)

 ```kotlin
 // SEND PATH
 Log.d("VOID_SECURITY", "🔒 [ENCRYPT_START] messageId=$id")
 Log.d("VOID_SECURITY", "🔑 [KEY_LOAD] privateKey from Keystore: ${key.size} 
 bytes")
 Log.d("VOID_SECURITY", "🔑 [KEY_LOAD] contactPublicKey: ${pubKey.size} bytes")
 Log.d("VOID_SECURITY", "🔐 [DH_AGREEMENT] sharedSecret: ${secret.size} bytes")
 Log.d("VOID_SECURITY", "🔐 [KEY_DERIVE] encKey: ${encKey.size} bytes, macKey: 
 ${macKey.size} bytes")
 Log.d("VOID_SECURITY", "🔒 [ENCRYPT] plaintext: ${plaintext.size} bytes →
 ciphertext: ${ciphertext.size} bytes")
 Log.d("VOID_SECURITY", "✓ [MAC_COMPUTE] MAC: ${mac.toHex()}")
 Log.d("VOID_SECURITY", "📦 [SERIALIZE] envelope: ${envelope.size} bytes")
 Log.d("VOID_SECURITY", "📤 [NETWORK_SEND] encryptedPayload (base64): 
 ${payload.substring(0, 20)}...")
 Log.d("VOID_SECURITY", "⚠️  [SECURITY_CHECK] Plaintext NEVER sent: ✓")

 // RECEIVE PATH
 Log.d("VOID_SECURITY", "📥 [NETWORK_RECEIVE] encryptedPayload: ${payload.size}
  bytes")
 Log.d("VOID_SECURITY", "📦 [DESERIALIZE] envelope extracted")
 Log.d("VOID_SECURITY", "🔑 [KEY_LOAD] privateKey from Keystore: ${key.size}
 bytes")
 Log.d("VOID_SECURITY", "🔑 [KEY_LOAD] senderPublicKey: ${pubKey.size} bytes")
 Log.d("VOID_SECURITY", "🔐 [DH_AGREEMENT] sharedSecret: ${secret.size} bytes")
 Log.d("VOID_SECURITY", "🔐 [KEY_DERIVE] encKey: ${encKey.size} bytes, macKey:
 ${macKey.size} bytes")
 Log.d("VOID_SECURITY", "✓ [MAC_VERIFY] Expected: ${expected.toHex()}, Got:
 ${actual.toHex()}")
 Log.d("VOID_SECURITY", "🔓 [DECRYPT] ciphertext: ${ciphertext.size} bytes → 
 plaintext: ${plaintext.size} bytes")
 Log.d("VOID_SECURITY", "⚠️  [SECURITY_CHECK] MAC verified before decrypt: ✓")
 ```

 ---

 ## 🧪 Security Test Cases

 ### Test 1: Server Cannot Read Messages ✅
 ```
 1. Send message "Secret Data"
 2. Check server logs
 3. VERIFY: Server logs show only base64 encrypted blob
 4. VERIFY: No "Secret Data" in server logs
 ```

 ### Test 2: Message Tampering Detection ✅
 ```
 1. Intercept encrypted message
 2. Modify ciphertext (flip one bit)
 3. Try to decrypt
 4. VERIFY: MAC verification fails
 5. VERIFY: Message rejected, not displayed
 ```

 ### Test 3: Replay Attack Prevention ✅
 ```
 1. Capture encrypted message
 2. Send same message again
 3. VERIFY: Receiver detects duplicate (messageId check)
 4. VERIFY: Message not displayed twice
 ```

 ### Test 4: Forward Secrecy ✅
 ```
 1. Send multiple messages
 2. VERIFY: Each message uses different nonce
 3. VERIFY: Compromising one message doesn't affect others
 ```

 ### Test 5: Man-in-the-Middle Detection ✅
 ```
 1. Contact exchange includes public key fingerprint
 2. VERIFY: User manually verifies fingerprint
 3. VERIFY: Messages encrypted with verified key only
 ```

 ---

 ## 🚀 Implementation Steps

 ### Step 1: Add Key Storage to Contact Model ✅
 - Add `publicKey: ByteArray` field
 - Add `identityKey: ByteArray` field
 - Store during contact exchange

 ### Step 2: Wire MessageEncryption in MessageRepository 🔨
 - Inject `MessageEncryption` dependency
 - Call `encrypt()` before sending
 - Call `decrypt()` after receiving
 - Add security logging

 ### Step 3: Update Contact Exchange 🔨
 - Generate key pair on identity creation
 - Include public key in contact request
 - Verify and store contact's public key

 ### Step 4: Add Security Logging 🔨
 - Debug logs at each crypto step
 - Verify logs in testing
 - Remove in production builds

 ### Step 5: End-to-End Testing 🔨
 - Test all security test cases
 - Verify server logs
 - Check logcat for security verification

 ---

 ## ✅ Acceptance Criteria

 **ALL must pass before deployment:**

 - [ ] Messages encrypted client-side (verified via logs)
 - [ ] Server logs show ONLY encrypted blobs (no plaintext)
 - [ ] MAC verified before decryption (verified via logs)
 - [ ] Keys loaded from Android Keystore (verified via logs)
 - [ ] Tampered messages rejected (test case passes)
 - [ ] No plaintext ever transmitted (network capture verification)
 - [ ] Contact public keys verified manually
 - [ ] Each message uses unique nonce (verified via logs)

 ---

 **Next**: Implement Step 2 - Wire MessageEncryption into MessageRepository
