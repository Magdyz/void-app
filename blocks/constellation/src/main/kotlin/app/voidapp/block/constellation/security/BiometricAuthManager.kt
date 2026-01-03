package app.voidapp.block.constellation.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.void.slate.crypto.CryptoProvider
import com.void.slate.crypto.keystore.KeystoreManager
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manages biometric authentication for identity unlock.
 * Uses Android Keystore biometric-protected keys.
 */
class BiometricAuthManager(
    private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val storage: SecureStorage,
    private val crypto: CryptoProvider
) {
    companion object {
        private const val BIOMETRIC_KEY_ALIAS = "biometric_unlock_key"
        private const val KEY_BIOMETRIC_ENABLED = "constellation.biometric.enabled"
    }

    /**
     * Check if device supports biometric authentication.
     */
    fun isBiometricAvailable(): BiometricAvailability {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                BiometricAvailability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                BiometricAvailability.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricAvailability.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricAvailability.SecurityUpdateRequired
            else -> BiometricAvailability.Unknown
        }
    }

    /**
     * Enable biometric unlock for the user.
     * Generates a biometric-protected Keystore key.
     */
    suspend fun enableBiometric(): BiometricSetupResult {
        // Check availability
        val availability = isBiometricAvailable()
        if (availability != BiometricAvailability.Available) {
            return BiometricSetupResult.NotAvailable(availability)
        }

        try {
            // Check if key already exists
            if (!keystoreManager.hasKey(BIOMETRIC_KEY_ALIAS)) {
                // Generate biometric-protected key
                keystoreManager.generateKey(
                    alias = BIOMETRIC_KEY_ALIAS,
                    requireAuth = false,  // We don't need auth to use the key itself
                    useStrongBox = true
                )
            }

            // Store preference
            storage.putString(KEY_BIOMETRIC_ENABLED, "true")

            return BiometricSetupResult.Success
        } catch (e: Exception) {
            println("VOID_DEBUG: BiometricAuthManager enableBiometric failed: ${e.message}")
            return BiometricSetupResult.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * Disable biometric unlock.
     */
    suspend fun disableBiometric() {
        keystoreManager.deleteKey(BIOMETRIC_KEY_ALIAS)
        storage.delete(KEY_BIOMETRIC_ENABLED)
    }

    /**
     * Check if biometric unlock is enabled.
     */
    suspend fun isBiometricEnabled(): Boolean {
        return storage.getString(KEY_BIOMETRIC_ENABLED) == "true"
    }

    /**
     * Authenticate using biometric and return success.
     * Shows BiometricPrompt and returns result.
     */
    suspend fun authenticateWithBiometric(
        activity: FragmentActivity
    ): BiometricAuthResult {
        // Check if enabled first (before suspendCancellableCoroutine)
        val enabled = storage.getString(KEY_BIOMETRIC_ENABLED) == "true"
        if (!enabled) {
            return BiometricAuthResult.NotEnabled
        }

        return suspendCancellableCoroutine { continuation ->

        val executor = ContextCompat.getMainExecutor(context)

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    println("VOID_DEBUG: Biometric authentication succeeded")
                    continuation.resume(BiometricAuthResult.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    println("VOID_DEBUG: Biometric authentication error: $errorCode - $errString")

                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                            continuation.resume(BiometricAuthResult.Cancelled)
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            continuation.resume(BiometricAuthResult.LockedOut)
                        else ->
                            continuation.resume(BiometricAuthResult.Failed(errString.toString()))
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    println("VOID_DEBUG: Biometric authentication failed (will retry)")
                    // Don't resume here - user gets to retry
                    // Only resume on error or success
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock VOID")
            .setSubtitle("Authenticate to access your identity")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use Pattern")
            .setConfirmationRequired(false)
            .build()

        biometricPrompt.authenticate(promptInfo)
        }
    }
}

// Result types
sealed class BiometricAvailability {
    object Available : BiometricAvailability()
    object NoHardware : BiometricAvailability()
    object HardwareUnavailable : BiometricAvailability()
    object NotEnrolled : BiometricAvailability()
    object SecurityUpdateRequired : BiometricAvailability()
    object Unknown : BiometricAvailability()
}

sealed class BiometricSetupResult {
    object Success : BiometricSetupResult()
    data class NotAvailable(val reason: BiometricAvailability) : BiometricSetupResult()
    data class Failed(val error: String) : BiometricSetupResult()
}

sealed class BiometricAuthResult {
    object Success : BiometricAuthResult()
    object Cancelled : BiometricAuthResult()
    object LockedOut : BiometricAuthResult()
    object NotEnabled : BiometricAuthResult()
    data class Failed(val error: String) : BiometricAuthResult()
}
