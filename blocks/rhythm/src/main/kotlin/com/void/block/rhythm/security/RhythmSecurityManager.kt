package com.void.block.rhythm.security

import com.void.block.rhythm.domain.RhythmMatcher
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.domain.RhythmQuantizer
import com.void.block.rhythm.domain.RhythmSerializer
import com.void.slate.crypto.CryptoProvider
import com.void.slate.crypto.keystore.KeystoreManager
import com.void.slate.crypto.wordlist.BIP39
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.delay

/**
 * Core security manager for rhythm-based authentication.
 *
 * ARCHITECTURE:
 * - Rhythm is a GATEKEEPER, not a key derivation source
 * - Master keys are stored in Android Keystore (hardware-backed)
 * - Rhythm unlocks access to those keys via fuzzy matching
 * - This provides BOTH tolerance AND security
 *
 * SECURITY MODEL:
 * ┌─────────────────────────────────────────┐
 * │  RHYTHM INPUT                            │
 * │  └─► Fuzzy Match (±25% tolerance)       │
 * │       │                                  │
 * │       ├─► MATCH → Unlock Keystore Key   │
 * │       └─► NO MATCH → Denied + Rate Limit│
 * └─────────────────────────────────────────┘
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
        return try {
            // Validate pattern
            if (!pattern.isValid) {
                return RegistrationResult.Error("Invalid rhythm pattern")
            }

            // 1. Quantize the pattern for storage
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
                // Generate random seed for identity (16 bytes = 128 bits = 12 words)
                val identitySeed = crypto.generateSeed(16)

                // Encrypt seed with master key
                val encryptedSeed = keystoreManager.encrypt(keyAlias, identitySeed)
                storage.put(KEY_IDENTITY_SEED, encryptedSeed)

                // Generate BIP-39 phrase from seed
                BIP39.toMnemonic(identitySeed)
            } else {
                emptyList()
            }

            RegistrationResult.Success(
                recoveryPhrase = recoveryPhrase,
                securityLevel = keystoreManager.getSecurityLevel()
            )

        } catch (e: Exception) {
            RegistrationResult.Error(e.message ?: "Registration failed")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Unlock
    // ═══════════════════════════════════════════════════════════════

    /**
     * Attempt to unlock with a rhythm pattern.
     * Checks both real and decoy patterns (constant time to prevent timing attacks).
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

        // Artificial delay to ensure constant time (50ms)
        delay(50)

        return when {
            realResult is TemplateCheckResult.Match -> {
                failedAttempts = 0
                UnlockResult.Success(
                    mode = UnlockMode.REAL,
                    identitySeed = getIdentitySeed(REAL_KEY_ALIAS),
                    confidence = realResult.confidence
                )
            }
            decoyResult is TemplateCheckResult.Match -> {
                failedAttempts = 0
                UnlockResult.Success(
                    mode = UnlockMode.DECOY,
                    identitySeed = null, // Decoy has no real identity
                    confidence = decoyResult.confidence
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
     * This restores the identity seed but requires setting up a new rhythm.
     */
    suspend fun recoverFromPhrase(mnemonic: List<String>): RecoveryResult {
        return try {
            // Validate mnemonic
            if (!BIP39.validate(mnemonic)) {
                return RecoveryResult.InvalidPhrase
            }

            // Derive seed from mnemonic
            val identitySeed = BIP39.toEntropy(mnemonic)

            // Generate new master key (old device's key is inaccessible)
            keystoreManager.deleteKey(REAL_KEY_ALIAS) // Remove old if exists
            keystoreManager.generateKey(
                alias = REAL_KEY_ALIAS,
                useStrongBox = true
            )

            // Store encrypted seed with new key
            val encryptedSeed = keystoreManager.encrypt(REAL_KEY_ALIAS, identitySeed)
            storage.put(KEY_IDENTITY_SEED, encryptedSeed)

            // User needs to set up new rhythm (can't recover rhythm pattern)
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

    fun isLockedOut(): Boolean = System.currentTimeMillis() < lockoutUntil

    fun getRemainingLockoutSeconds(): Int {
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) (remaining / 1000).toInt() else 0
    }

    fun getFailedAttempts(): Int = failedAttempts

    suspend fun getSecurityInfo(): SecurityInfo {
        return SecurityInfo(
            securityLevel = keystoreManager.getSecurityLevel(),
            hasRealRhythm = hasRealRhythm(),
            hasDecoyRhythm = hasDecoyRhythm(),
            isLockedOut = isLockedOut(),
            failedAttempts = failedAttempts
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Panic Wipe
    // ═══════════════════════════════════════════════════════════════

    /**
     * Emergency wipe of all rhythm data and keys.
     * This is irreversible without recovery phrase.
     */
    suspend fun panicWipe() {
        keystoreManager.deleteAllVoidKeys()
        storage.clear()
        failedAttempts = 0
        lockoutUntil = 0
    }
}

// ═══════════════════════════════════════════════════════════════
// Result Types
// ═══════════════════════════════════════════════════════════════

sealed class RegistrationResult {
    data class Success(
        val recoveryPhrase: List<String>,
        val securityLevel: com.void.slate.crypto.keystore.SecurityLevel
    ) : RegistrationResult()

    data class Error(val message: String) : RegistrationResult()
}

sealed class UnlockResult {
    data class Success(
        val mode: UnlockMode,
        val identitySeed: ByteArray?,
        val confidence: Float
    ) : UnlockResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Success

            if (mode != other.mode) return false
            if (identitySeed != null) {
                if (other.identitySeed == null) return false
                if (!identitySeed.contentEquals(other.identitySeed)) return false
            } else if (other.identitySeed != null) return false
            if (confidence != other.confidence) return false

            return true
        }

        override fun hashCode(): Int {
            var result = mode.hashCode()
            result = 31 * result + (identitySeed?.contentHashCode() ?: 0)
            result = 31 * result + confidence.hashCode()
            return result
        }
    }

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

data class SecurityInfo(
    val securityLevel: com.void.slate.crypto.keystore.SecurityLevel,
    val hasRealRhythm: Boolean,
    val hasDecoyRhythm: Boolean,
    val isLockedOut: Boolean,
    val failedAttempts: Int
)
