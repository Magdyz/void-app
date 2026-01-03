package app.voidapp.block.constellation.ui.unlock

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.voidapp.block.constellation.domain.*
import app.voidapp.block.constellation.security.ConstellationSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for constellation unlock screen.
 */
class ConstellationUnlockViewModel(
    private val securityManager: ConstellationSecurityManager,
    private val matcher: ConstellationMatcher,
    private val starGenerator: StarGenerator,
    private val quantizer: StarQuantizer,
    private val getIdentitySeed: suspend () -> ByteArray?
) : ViewModel() {

    private val _state = MutableStateFlow<ConstellationUnlockState>(ConstellationUnlockState.Loading)
    val state: StateFlow<ConstellationUnlockState> = _state.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private var storedPatternLength: Int = ConstellationSecurityManager.MIN_LANDMARKS  // Actual stored pattern length
    private var isV2Pattern: Boolean = false
    private var isInitialized: Boolean = false  // Prevent multiple initializations

    init {
        // Check if biometric is enabled
        viewModelScope.launch {
            _biometricEnabled.value = securityManager.isBiometricEnabled()
            println("VOID_DEBUG: UnlockVM - Biometric enabled: ${_biometricEnabled.value}")
        }
    }

    fun initialize(width: Int = 1080, height: Int = 1920) {
        // Prevent re-initialization if already initialized and in Ready/Success state
        if (isInitialized && (_state.value is ConstellationUnlockState.Ready || _state.value is ConstellationUnlockState.Success)) {
            return
        }

        viewModelScope.launch {
            _state.value = ConstellationUnlockState.Loading
            isInitialized = true

            // Check if using V2 pattern
            isV2Pattern = securityManager.isV2Pattern()

            // Load the stored pattern length (critical for auto-unlock timing)
            storedPatternLength = securityManager.getStoredPatternLength()
            println("VOID_DEBUG: UnlockVM - Stored pattern length: $storedPatternLength")

            // Check if locked out
            if (securityManager.isLockedOut()) {
                val lockoutEnd = securityManager.getLockoutEndTime()
                _state.value = ConstellationUnlockState.LockedOut(lockoutEnd)
                return@launch
            }

            val result = withContext(Dispatchers.Default) {
                try {
                    val identitySeed = getIdentitySeed()
                        ?: return@withContext null

                    starGenerator.generate(identitySeed, width, height)
                } catch (e: Exception) {
                    null
                }
            }

            if (result != null) {
                val failedAttempts = securityManager.getFailedAttempts()
                val attemptsRemaining = ConstellationSecurityManager.MAX_ATTEMPTS_BEFORE_LOCKOUT - failedAttempts

                _state.value = ConstellationUnlockState.Ready(
                    constellation = result.bitmap,
                    landmarks = result.landmarks,  // V2: Store landmarks
                    tappedStars = emptyList(),
                    attemptsRemaining = attemptsRemaining,
                    isV2 = isV2Pattern
                )
            } else {
                _state.value = ConstellationUnlockState.Error("Failed to generate constellation")
            }
        }
    }

    fun onStarTapped(tap: TapPoint, screenWidth: Int, screenHeight: Int) {
        val currentState = _state.value
        if (currentState !is ConstellationUnlockState.Ready) return

        println("VOID_DEBUG: UnlockVM - Tap received at (${tap.x}, ${tap.y}), isV2: ${currentState.isV2}")

        val currentTaps = currentState.tappedStars
        val maxTaps = if (currentState.isV2) ConstellationSecurityManager.MAX_LANDMARKS else ConstellationSecurityManager.MAX_STARS
        if (currentTaps.size >= maxTaps) {
            println("VOID_DEBUG: UnlockVM - Max taps reached, ignoring")
            return
        }

        // For V2, check if tap hits a landmark
        if (currentState.isV2) {
            val hitLandmark = matcher.findNearestLandmark(tap, currentState.landmarks)
            if (hitLandmark == null) {
                println("VOID_DEBUG: UnlockVM - Tap missed all landmarks, ignoring")
                return
            }
        }

        val newTaps = currentTaps + tap

        println("VOID_DEBUG: UnlockVM - Taps: ${newTaps.size}/${storedPatternLength}")

        _state.value = currentState.copy(tappedStars = newTaps)

        // Auto-attempt unlock when stored pattern length is reached
        // This ensures we wait for the full pattern before attempting unlock
        if (newTaps.size >= storedPatternLength) {
            println("VOID_DEBUG: UnlockVM - Attempting unlock with ${newTaps.size} taps (expected: $storedPatternLength)")
            attemptUnlock(newTaps, currentState.landmarks, currentState.isV2, screenWidth, screenHeight)
        }
    }

    private fun attemptUnlock(
        taps: List<TapPoint>,
        landmarks: List<StarGenerator.Landmark>,
        isV2: Boolean,
        screenWidth: Int,
        screenHeight: Int
    ) {
        viewModelScope.launch {
            val result = if (isV2) {
                // V2: Use landmark pattern with gravity well
                val landmarkPattern = matcher.createLandmarkPattern(taps, landmarks)
                securityManager.unlockV2(landmarkPattern)
            } else {
                // V1: Use old grid-based pattern (backward compatibility)
                val pattern = ConstellationPattern(
                    stars = quantizer.normalizeAll(taps, screenWidth, screenHeight),
                    quality = 0
                )
                securityManager.unlock(pattern)
            }

            // Get current constellation to preserve it on failure
            val currentState = _state.value
            val currentConstellation = (currentState as? ConstellationUnlockState.Ready)?.constellation
            val currentLandmarks = (currentState as? ConstellationUnlockState.Ready)?.landmarks
            val currentIsV2 = (currentState as? ConstellationUnlockState.Ready)?.isV2 ?: isV2

            _state.value = when (result) {
                is ConstellationResult.Success -> ConstellationUnlockState.Success
                is ConstellationResult.Failure -> {
                    // CRITICAL: Preserve constellation and landmarks so retry shows SAME image
                    ConstellationUnlockState.Failure(
                        constellation = currentConstellation ?: return@launch,
                        landmarks = currentLandmarks ?: emptyList(),
                        attemptsRemaining = result.attemptsRemaining,
                        message = "Incorrect pattern. ${result.attemptsRemaining} attempts remaining.",
                        isV2 = currentIsV2
                    )
                }
                is ConstellationResult.LockedOut -> {
                    val lockoutEnd = securityManager.getLockoutEndTime()
                    ConstellationUnlockState.LockedOut(lockoutEnd)
                }
                is ConstellationResult.InvalidPattern -> {
                    ConstellationUnlockState.Error("Invalid pattern")
                }
                is ConstellationResult.BiometricCancelled -> {
                    // Should not happen in pattern unlock, but handle for exhaustiveness
                    ConstellationUnlockState.Ready(
                        constellation = currentConstellation ?: return@launch,
                        landmarks = currentLandmarks ?: emptyList(),
                        tappedStars = emptyList(),
                        attemptsRemaining = securityManager.getFailedAttempts(),
                        isV2 = currentIsV2
                    )
                }
            }
        }
    }

    fun onReset() {
        val currentState = _state.value
        if (currentState is ConstellationUnlockState.Ready) {
            _state.value = currentState.copy(tappedStars = emptyList())
        } else if (currentState is ConstellationUnlockState.Failure) {
            // CRITICAL: Restore constellation from Failure state - NEVER regenerate!
            // The constellation must be identical across all unlock attempts
            _state.value = ConstellationUnlockState.Ready(
                constellation = currentState.constellation,
                landmarks = currentState.landmarks,
                tappedStars = emptyList(),
                attemptsRemaining = currentState.attemptsRemaining,
                isV2 = currentState.isV2
            )
        }
    }

    /**
     * Trigger biometric authentication for unlock.
     */
    fun triggerBiometric(activity: androidx.fragment.app.FragmentActivity) {
        viewModelScope.launch {
            println("VOID_DEBUG: UnlockVM - Triggering biometric authentication")

            when (val result = securityManager.unlockWithBiometric(activity)) {
                is ConstellationResult.Success -> {
                    println("VOID_DEBUG: UnlockVM - Biometric unlock successful")
                    _state.value = ConstellationUnlockState.Success
                }
                is ConstellationResult.Failure -> {
                    println("VOID_DEBUG: UnlockVM - Biometric unlock failed, attempts remaining: ${result.attemptsRemaining}")
                    // Show failure and allow pattern fallback
                    val currentState = _state.value
                    if (currentState is ConstellationUnlockState.Ready) {
                        _state.value = ConstellationUnlockState.Failure(
                            constellation = currentState.constellation,
                            landmarks = currentState.landmarks,
                            attemptsRemaining = result.attemptsRemaining,
                            message = "Biometric failed. ${result.attemptsRemaining} attempts remaining",
                            isV2 = currentState.isV2
                        )
                    }
                }
                is ConstellationResult.LockedOut -> {
                    println("VOID_DEBUG: UnlockVM - Account locked out")
                    val lockoutEnd = securityManager.getLockoutEndTime()
                    _state.value = ConstellationUnlockState.LockedOut(lockoutEnd)
                }
                is ConstellationResult.BiometricCancelled -> {
                    println("VOID_DEBUG: UnlockVM - Biometric cancelled, staying in current state")
                    // Don't change state - user can try pattern or biometric again
                }
                is ConstellationResult.InvalidPattern -> {
                    println("VOID_DEBUG: UnlockVM - Biometric invalid/not setup")
                    _state.value = ConstellationUnlockState.Error("Biometric not available")
                }
            }
        }
    }
}

sealed class ConstellationUnlockState {
    object Loading : ConstellationUnlockState()

    data class Ready(
        val constellation: Bitmap,
        val landmarks: List<StarGenerator.Landmark>,  // V2: Store landmarks
        val tappedStars: List<TapPoint>,
        val attemptsRemaining: Int,
        val isV2: Boolean = false  // Track if using V2 pattern
    ) : ConstellationUnlockState()

    object Success : ConstellationUnlockState()

    data class Failure(
        val constellation: Bitmap,  // CRITICAL: Keep constellation for retry
        val landmarks: List<StarGenerator.Landmark>,  // CRITICAL: Keep landmarks for retry
        val attemptsRemaining: Int,
        val message: String,
        val isV2: Boolean = false
    ) : ConstellationUnlockState()

    data class LockedOut(val lockoutEndTime: Long) : ConstellationUnlockState()
    data class Error(val message: String) : ConstellationUnlockState()
}
