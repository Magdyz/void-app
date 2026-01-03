package app.voidapp.block.constellation.security

import app.voidapp.block.constellation.domain.*
import com.void.slate.crypto.CryptoProvider
import com.void.slate.crypto.keystore.KeystoreManager
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages constellation lock security operations.
 * Follows RhythmSecurityManager pattern for consistency.
 * Now supports biometric unlock as alternative to constellation pattern.
 */
class ConstellationSecurityManager(
    private val keystoreManager: KeystoreManager,
    private val storage: SecureStorage,
    private val crypto: CryptoProvider,
    private val matcher: ConstellationMatcher,
    private val getIdentitySeed: suspend () -> ByteArray?,
    private val biometricManager: BiometricAuthManager? = null  // Optional biometric support
) {
    companion object {
        private const val KEY_ALIAS = "constellation_master_key"

        // Storage keys
        private const val KEY_REAL_PATTERN_ENCRYPTED = "constellation.pattern.real.encrypted"
        private const val KEY_REAL_PATTERN_V2_ENCRYPTED = "constellation.pattern.v2.real.encrypted"
        private const val KEY_DECOY_PATTERN_ENCRYPTED = "constellation.pattern.decoy.encrypted"
        private const val KEY_VERIFICATION_HASH = "constellation.verification_hash"
        private const val KEY_ALGORITHM_VERSION = "constellation.algorithm_version"
        private const val KEY_PATTERN_VERSION = "constellation.pattern_version"  // v1 or v2
        private const val KEY_FAILED_ATTEMPTS = "constellation.failed_attempts"
        private const val KEY_LOCKOUT_END_TIME = "constellation.lockout_end_time"
        private const val KEY_TOTAL_ATTEMPTS = "constellation.total_attempts"

        // Security constants
        const val MIN_STARS = 3  // Quick and easy for one-handed use
        const val MAX_STARS = 5  // Max for UX - quick unlock
        const val MIN_LANDMARKS = 3  // V2: Minimum landmark taps (8 unique shapes)
        const val MAX_LANDMARKS = 5  // V2: Maximum landmark taps (8^5 = 32,768 combinations)
        const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
        const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
        const val MAX_TOTAL_ATTEMPTS_BEFORE_WIPE = 20
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Check if constellation is set up (V1 or V2).
     */
    suspend fun hasRealConstellation(): Boolean {
        mutex.lock()
        try {
            return storage.contains(KEY_REAL_PATTERN_ENCRYPTED) ||
                   storage.contains(KEY_REAL_PATTERN_V2_ENCRYPTED)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Check if using V2 landmark pattern.
     */
    suspend fun isV2Pattern(): Boolean {
        mutex.lock()
        try {
            return storage.getString(KEY_PATTERN_VERSION) == "2"
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Get the length of the stored pattern (for auto-unlock timing).
     * Returns the number of taps/landmarks in the stored pattern, or MIN_LANDMARKS if not found.
     */
    suspend fun getStoredPatternLength(): Int {
        mutex.lock()
        try {
            val isV2 = storage.getString(KEY_PATTERN_VERSION) == "2"

            val encryptedPattern = if (isV2) {
                storage.get(KEY_REAL_PATTERN_V2_ENCRYPTED)
            } else {
                storage.get(KEY_REAL_PATTERN_ENCRYPTED)
            } ?: return MIN_LANDMARKS

            return try {
                val decrypted = keystoreManager.decrypt(KEY_ALIAS, encryptedPattern)
                if (isV2) {
                    val pattern = json.decodeFromString<LandmarkPattern>(String(decrypted))
                    pattern.landmarkIndices.size
                } else {
                    val pattern = json.decodeFromString<ConstellationPattern>(String(decrypted))
                    pattern.stars.size
                }
            } catch (e: Exception) {
                MIN_LANDMARKS  // Fallback to minimum on error
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Check if setup is complete (includes verification hash).
     */
    suspend fun isSetupComplete(): Boolean {
        mutex.lock()
        try {
            return storage.contains(KEY_REAL_PATTERN_ENCRYPTED) && storage.contains(KEY_VERIFICATION_HASH)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Register the real constellation pattern.
     * Requires confirmation pattern to ensure user can reproduce it.
     */
    suspend fun registerRealConstellation(
        pattern: ConstellationPattern,
        confirmPattern: ConstellationPattern
    ): RegistrationResult {
        println("VOID_DEBUG: SecurityManager.registerRealConstellation started")

        mutex.lock()
        try {
            // Validate size
            println("VOID_DEBUG: Validating size - pattern.stars.size=${pattern.stars.size}")
            if (pattern.stars.size !in MIN_STARS..MAX_STARS) {
                println("VOID_DEBUG: Size validation failed!")
                return RegistrationResult.InvalidQuality(0, MIN_STARS)
            }

            // Validate individual pattern
            println("VOID_DEBUG: Calling matcher.validatePattern()")
            val validationResult = matcher.validatePattern(pattern)
            println("VOID_DEBUG: validatePattern returned: $validationResult")
            if (validationResult !is RegistrationResult.Success) {
                println("VOID_DEBUG: Pattern validation failed!")
                return validationResult
            }

            // Verify confirmation matches
            println("VOID_DEBUG: Calling matcher.matches()")
            val matches = matcher.matches(pattern, confirmPattern)
            println("VOID_DEBUG: matcher.matches returned: $matches")
            if (!matches) {
                println("VOID_DEBUG: Patterns don't match!")
                return RegistrationResult.MismatchWithConfirmation
            }

            // Ensure Keystore key exists
            println("VOID_DEBUG: Checking Keystore key")
            if (!keystoreManager.hasKey(KEY_ALIAS)) {
                println("VOID_DEBUG: Generating Keystore key")
                keystoreManager.generateKey(
                    alias = KEY_ALIAS,
                    requireAuth = false,
                    useStrongBox = true
                )
                println("VOID_DEBUG: Keystore key generated")
            } else {
                println("VOID_DEBUG: Keystore key already exists")
            }

            // Serialize and encrypt pattern
            println("VOID_DEBUG: Serializing pattern")
            val patternJson = json.encodeToString(pattern)
            println("VOID_DEBUG: Encrypting pattern")
            val encrypted = keystoreManager.encrypt(KEY_ALIAS, patternJson.toByteArray())
            println("VOID_DEBUG: Pattern encrypted")

            // Store encrypted pattern
            println("VOID_DEBUG: Storing encrypted pattern")
            storage.put(KEY_REAL_PATTERN_ENCRYPTED, encrypted)
            println("VOID_DEBUG: Pattern stored - returning Success")

            return RegistrationResult.Success
        } finally {
            mutex.unlock()
        }
    }

    /**
     * V2: Register landmark-based constellation pattern.
     * Uses gravity well for tap tolerance.
     */
    suspend fun registerRealConstellationV2(
        pattern: LandmarkPattern,
        confirmPattern: LandmarkPattern
    ): RegistrationResult {
        println("VOID_DEBUG: SecurityManager.registerRealConstellationV2 started")

        mutex.lock()
        try {
            // Validate size
            println("VOID_DEBUG: Validating size - pattern.landmarkIndices.size=${pattern.landmarkIndices.size}")
            if (pattern.landmarkIndices.size !in MIN_LANDMARKS..MAX_LANDMARKS) {
                println("VOID_DEBUG: Size validation failed!")
                return RegistrationResult.InvalidQuality(0, MIN_LANDMARKS)
            }

            // Validate individual pattern
            println("VOID_DEBUG: Calling matcher.validateLandmarkPattern()")
            val validationResult = matcher.validateLandmarkPattern(pattern)
            println("VOID_DEBUG: validateLandmarkPattern returned: $validationResult")
            if (validationResult !is RegistrationResult.Success) {
                println("VOID_DEBUG: Pattern validation failed!")
                return validationResult
            }

            // Verify confirmation matches
            println("VOID_DEBUG: Calling matcher.matchesV2()")
            val matches = matcher.matchesV2(pattern, confirmPattern)
            println("VOID_DEBUG: matcher.matchesV2 returned: $matches")
            if (!matches) {
                println("VOID_DEBUG: Patterns don't match!")
                return RegistrationResult.MismatchWithConfirmation
            }

            // Ensure Keystore key exists
            println("VOID_DEBUG: Checking Keystore key")
            if (!keystoreManager.hasKey(KEY_ALIAS)) {
                println("VOID_DEBUG: Generating Keystore key")
                keystoreManager.generateKey(
                    alias = KEY_ALIAS,
                    requireAuth = false,
                    useStrongBox = true
                )
                println("VOID_DEBUG: Keystore key generated")
            } else {
                println("VOID_DEBUG: Keystore key already exists")
            }

            // Serialize and encrypt pattern
            println("VOID_DEBUG: Serializing pattern")
            val patternJson = json.encodeToString(pattern)
            println("VOID_DEBUG: Encrypting pattern")
            val encrypted = keystoreManager.encrypt(KEY_ALIAS, patternJson.toByteArray())
            println("VOID_DEBUG: Pattern encrypted")

            // Store encrypted pattern
            println("VOID_DEBUG: Storing encrypted pattern")
            storage.put(KEY_REAL_PATTERN_V2_ENCRYPTED, encrypted)
            storage.putString(KEY_PATTERN_VERSION, "2")
            println("VOID_DEBUG: Pattern stored - returning Success")

            return RegistrationResult.Success
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Store verification hash after constellation is generated.
     */
    suspend fun storeVerificationHash(hash: String, version: Int) {
        mutex.lock()
        try {
            storage.putString(KEY_VERIFICATION_HASH, hash)
            storage.putString(KEY_ALGORITHM_VERSION, version.toString())
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Verify constellation integrity hasn't changed.
     */
    suspend fun verifyConstellationIntegrity(currentHash: String): Boolean {
        mutex.lock()
        try {
            val storedHash = storage.getString(KEY_VERIFICATION_HASH) ?: return false
            return storedHash == currentHash
        } finally {
            mutex.unlock()
        }
    }

    /**
     * V2: Attempt to unlock with landmark pattern.
     */
    suspend fun unlockV2(attempt: LandmarkPattern): ConstellationResult {
        mutex.lock()
        try {
            // Check lockout
            val lockoutEnd = storage.getString(KEY_LOCKOUT_END_TIME)?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() < lockoutEnd) {
                return ConstellationResult.LockedOut
            }

            // Load stored pattern
            val encryptedPattern = storage.get(KEY_REAL_PATTERN_V2_ENCRYPTED)
                ?: return ConstellationResult.InvalidPattern

            val storedPattern = try {
                val decrypted = keystoreManager.decrypt(KEY_ALIAS, encryptedPattern)
                json.decodeFromString<LandmarkPattern>(String(decrypted))
            } catch (e: Exception) {
                return ConstellationResult.InvalidPattern
            }

            // Verify pattern (constant-time)
            if (matcher.matchesV2(attempt, storedPattern)) {
                // Success - reset counters
                resetFailedAttempts()

                // Get identity seed hash for session
                val identitySeed = getIdentitySeed()
                val seedHash = identitySeed?.let { crypto.hash(it) }
                    ?: return ConstellationResult.InvalidPattern

                return ConstellationResult.Success(seedHash)
            }

            // Failure - increment counters
            val failedAttempts = incrementFailedAttempts()
            val totalAttempts = incrementTotalAttempts()

            // Check for wipe threshold
            if (totalAttempts >= MAX_TOTAL_ATTEMPTS_BEFORE_WIPE) {
                panicWipe()
                return ConstellationResult.LockedOut
            }

            // Check for lockout
            if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                val lockoutEndTime = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                storage.putString(KEY_LOCKOUT_END_TIME, lockoutEndTime.toString())
                return ConstellationResult.LockedOut
            }

            return ConstellationResult.Failure(MAX_ATTEMPTS_BEFORE_LOCKOUT - failedAttempts)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * V1: Attempt to unlock with constellation pattern (backward compatibility).
     */
    suspend fun unlock(attempt: ConstellationPattern): ConstellationResult {
        mutex.lock()
        try {
            // Check lockout
            val lockoutEnd = storage.getString(KEY_LOCKOUT_END_TIME)?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() < lockoutEnd) {
                return ConstellationResult.LockedOut
            }

            // Load stored pattern
            val encryptedPattern = storage.get(KEY_REAL_PATTERN_ENCRYPTED)
                ?: return ConstellationResult.InvalidPattern

            val storedPattern = try {
                val decrypted = keystoreManager.decrypt(KEY_ALIAS, encryptedPattern)
                json.decodeFromString<ConstellationPattern>(String(decrypted))
            } catch (e: Exception) {
                return ConstellationResult.InvalidPattern
            }

            // Verify pattern (constant-time)
            if (matcher.matches(attempt, storedPattern)) {
                // Success - reset counters
                resetFailedAttempts()

                // Get identity seed hash for session
                val identitySeed = getIdentitySeed()
                val seedHash = identitySeed?.let { crypto.hash(it) }
                    ?: return ConstellationResult.InvalidPattern

                return ConstellationResult.Success(seedHash)
            }

            // Failure - increment counters
            val failedAttempts = incrementFailedAttempts()
            val totalAttempts = incrementTotalAttempts()

            // Check for wipe threshold
            if (totalAttempts >= MAX_TOTAL_ATTEMPTS_BEFORE_WIPE) {
                panicWipe()
                return ConstellationResult.LockedOut
            }

            // Check for lockout
            if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                val lockoutEndTime = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                storage.putString(KEY_LOCKOUT_END_TIME, lockoutEndTime.toString())
                return ConstellationResult.LockedOut
            }

            return ConstellationResult.Failure(MAX_ATTEMPTS_BEFORE_LOCKOUT - failedAttempts)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Recover using BIP-39 mnemonic phrase.
     * Note: Actual mnemonic validation would be done by IdentityRepository.
     */
    suspend fun recoverFromPhrase(mnemonic: List<String>): Boolean {
        mutex.lock()
        try {
            // TODO: Validate mnemonic matches stored identity
            // This would integrate with IdentityRepository's recovery flow

            // Clear constellation pattern (V1 and V2)
            storage.delete(KEY_REAL_PATTERN_ENCRYPTED)
            storage.delete(KEY_REAL_PATTERN_V2_ENCRYPTED)
            storage.delete(KEY_DECOY_PATTERN_ENCRYPTED)
            storage.delete(KEY_PATTERN_VERSION)

            // Reset security state
            resetFailedAttempts()
            resetTotalAttempts()
            storage.delete(KEY_LOCKOUT_END_TIME)

            // Keep verification hash for integrity check on next setup

            return true
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Emergency wipe (called after max total attempts).
     */
    suspend fun panicWipe() {
        mutex.lock()
        try {
            // Wipe all constellation data (V1 and V2)
            storage.delete(KEY_REAL_PATTERN_ENCRYPTED)
            storage.delete(KEY_REAL_PATTERN_V2_ENCRYPTED)
            storage.delete(KEY_DECOY_PATTERN_ENCRYPTED)
            storage.delete(KEY_VERIFICATION_HASH)
            storage.delete(KEY_ALGORITHM_VERSION)
            storage.delete(KEY_PATTERN_VERSION)
            resetFailedAttempts()
            resetTotalAttempts()
            storage.delete(KEY_LOCKOUT_END_TIME)

            // Delete Keystore key
            keystoreManager.deleteKey(KEY_ALIAS)

            // Note: Identity wipe would be coordinated via EventBus
        } finally {
            mutex.unlock()
        }
    }

    // Attempt tracking (persisted)

    private suspend fun incrementFailedAttempts(): Int {
        val current = storage.getString(KEY_FAILED_ATTEMPTS)?.toIntOrNull() ?: 0
        val updated = current + 1
        storage.putString(KEY_FAILED_ATTEMPTS, updated.toString())
        return updated
    }

    private suspend fun resetFailedAttempts() {
        storage.delete(KEY_FAILED_ATTEMPTS)
    }

    private suspend fun incrementTotalAttempts(): Int {
        val current = storage.getString(KEY_TOTAL_ATTEMPTS)?.toIntOrNull() ?: 0
        val updated = current + 1
        storage.putString(KEY_TOTAL_ATTEMPTS, updated.toString())
        return updated
    }

    private suspend fun resetTotalAttempts() {
        storage.delete(KEY_TOTAL_ATTEMPTS)
    }

    suspend fun getFailedAttempts(): Int {
        return storage.getString(KEY_FAILED_ATTEMPTS)?.toIntOrNull() ?: 0
    }

    suspend fun getLockoutEndTime(): Long {
        return storage.getString(KEY_LOCKOUT_END_TIME)?.toLongOrNull() ?: 0L
    }

    suspend fun isLockedOut(): Boolean {
        val lockoutEnd = getLockoutEndTime()
        return System.currentTimeMillis() < lockoutEnd
    }

    /**
     * Clear all constellation data (for testing or reset).
     */
    suspend fun clear() {
        mutex.lock()
        try {
            storage.delete(KEY_REAL_PATTERN_ENCRYPTED)
            storage.delete(KEY_REAL_PATTERN_V2_ENCRYPTED)
            storage.delete(KEY_DECOY_PATTERN_ENCRYPTED)
            storage.delete(KEY_VERIFICATION_HASH)
            storage.delete(KEY_ALGORITHM_VERSION)
            storage.delete(KEY_PATTERN_VERSION)
            resetFailedAttempts()
            resetTotalAttempts()
            storage.delete(KEY_LOCKOUT_END_TIME)
        } finally {
            mutex.unlock()
        }
    }

    // ========== BIOMETRIC UNLOCK METHODS ==========

    /**
     * Check if biometric unlock is enabled for this user.
     */
    suspend fun isBiometricEnabled(): Boolean {
        return biometricManager?.isBiometricEnabled() ?: false
    }

    /**
     * Unlock using biometric authentication.
     * Alternative to constellation pattern unlock.
     */
    suspend fun unlockWithBiometric(
        activity: androidx.fragment.app.FragmentActivity
    ): ConstellationResult {
        if (biometricManager == null) {
            return ConstellationResult.InvalidPattern
        }

        mutex.lock()
        try {
            // Check lockout (same rules apply)
            val lockoutEnd = storage.getString(KEY_LOCKOUT_END_TIME)?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() < lockoutEnd) {
                return ConstellationResult.LockedOut
            }

            // Attempt biometric authentication
            when (val result = biometricManager.authenticateWithBiometric(activity)) {
                is BiometricAuthResult.Success -> {
                    // Success - reset counters and get identity seed
                    resetFailedAttempts()

                    val identitySeed = getIdentitySeed()
                    val seedHash = identitySeed?.let { crypto.hash(it) }
                        ?: return ConstellationResult.InvalidPattern

                    return ConstellationResult.Success(seedHash)
                }
                BiometricAuthResult.Cancelled -> {
                    // User cancelled - don't count as failed attempt
                    return ConstellationResult.BiometricCancelled
                }
                BiometricAuthResult.LockedOut -> {
                    return ConstellationResult.LockedOut
                }
                is BiometricAuthResult.Failed -> {
                    // Biometric failed - increment attempts
                    val failedAttempts = incrementFailedAttempts()
                    val totalAttempts = incrementTotalAttempts()

                    // Check for wipe threshold
                    if (totalAttempts >= MAX_TOTAL_ATTEMPTS_BEFORE_WIPE) {
                        panicWipe()
                        return ConstellationResult.LockedOut
                    }

                    // Check for lockout
                    if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                        val lockoutEndTime = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                        storage.putString(KEY_LOCKOUT_END_TIME, lockoutEndTime.toString())
                        return ConstellationResult.LockedOut
                    }

                    return ConstellationResult.Failure(MAX_ATTEMPTS_BEFORE_LOCKOUT - failedAttempts)
                }
                BiometricAuthResult.NotEnabled -> {
                    return ConstellationResult.InvalidPattern
                }
            }
        } finally {
            mutex.unlock()
        }
    }
}
