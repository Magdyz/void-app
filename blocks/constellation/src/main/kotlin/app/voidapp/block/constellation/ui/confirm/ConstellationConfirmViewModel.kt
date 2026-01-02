package app.voidapp.block.constellation.ui.confirm

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
 * ViewModel for constellation confirmation screen.
 */
class ConstellationConfirmViewModel(
    private val securityManager: ConstellationSecurityManager,
    private val matcher: ConstellationMatcher,
    private val starGenerator: StarGenerator,
    private val quantizer: StarQuantizer,
    private val getIdentitySeed: suspend () -> ByteArray?
) : ViewModel() {

    private val _state = MutableStateFlow<ConstellationConfirmState>(ConstellationConfirmState.Loading)
    val state: StateFlow<ConstellationConfirmState> = _state.asStateFlow()

    private var generationMetadata: StarGenerator.GenerationMetadata? = null

    fun initialize(
        firstPattern: LandmarkPattern,  // V2: Use LandmarkPattern
        landmarks: List<StarGenerator.Landmark>,  // V2: Pass landmarks
        constellation: Bitmap,  // Reuse exact same bitmap from setup
        metadata: StarGenerator.GenerationMetadata?,
        width: Int,
        height: Int
    ) {
        viewModelScope.launch {
            _state.value = ConstellationConfirmState.Loading

            generationMetadata = metadata  // Store metadata for verification hash

            // No regeneration! Use the exact same constellation from setup
            _state.value = ConstellationConfirmState.Ready(
                constellation = constellation,  // Same bitmap = same visual
                landmarks = landmarks,  // V2: Store landmarks
                firstPattern = firstPattern,
                tappedStars = emptyList()
            )
        }
    }

    fun onStarTapped(tap: TapPoint, screenWidth: Int, screenHeight: Int) {
        val currentState = _state.value
        if (currentState !is ConstellationConfirmState.Ready) return

        println("VOID_DEBUG: ConfirmVM - Tap received at (${tap.x}, ${tap.y})")

        val currentTaps = currentState.tappedStars
        val requiredCount = currentState.firstPattern.landmarkIndices.size  // V2: Use landmarkIndices

        if (currentTaps.size >= requiredCount) {
            println("VOID_DEBUG: ConfirmVM - Required count reached, ignoring tap")
            return
        }

        // Check if tap hits a landmark before adding it
        val hitLandmark = matcher.findNearestLandmark(tap, currentState.landmarks)
        if (hitLandmark == null) {
            println("VOID_DEBUG: ConfirmVM - Tap missed all landmarks, ignoring")
            return
        }

        val newTaps = currentTaps + tap

        println("VOID_DEBUG: ConfirmVM - Taps: ${newTaps.size}/${requiredCount}")

        _state.value = currentState.copy(tappedStars = newTaps)

        // Don't auto-confirm - wait for user to press Confirm button
    }

    fun confirmPattern(screenWidth: Int, screenHeight: Int) {
        println("VOID_DEBUG: confirmPattern called - screenWidth=$screenWidth, screenHeight=$screenHeight")
        val currentState = _state.value
        println("VOID_DEBUG: Current state = $currentState")

        if (currentState !is ConstellationConfirmState.Ready) {
            println("VOID_DEBUG: State is not Ready, returning")
            return
        }

        val confirmTaps = currentState.tappedStars
        val firstPattern = currentState.firstPattern
        println("VOID_DEBUG: Confirming pattern - firstPattern.landmarkIndices.size=${firstPattern.landmarkIndices.size}, confirmTaps.size=${confirmTaps.size}")

        validatePattern(firstPattern, currentState.landmarks, confirmTaps, screenWidth, screenHeight)
    }

    private fun validatePattern(
        firstPattern: LandmarkPattern,  // V2: Use LandmarkPattern
        landmarks: List<StarGenerator.Landmark>,  // V2: Pass landmarks
        confirmTaps: List<TapPoint>,
        screenWidth: Int,
        screenHeight: Int
    ) {
        viewModelScope.launch {
            println("VOID_DEBUG: validatePattern started")

            // V2: Create landmark pattern using gravity well
            val confirmPattern = matcher.createLandmarkPattern(confirmTaps, landmarks)
            println("VOID_DEBUG: Created confirm pattern - landmarkIndices.size=${confirmPattern.landmarkIndices.size}")

            val result = withContext(Dispatchers.IO) {
                securityManager.registerRealConstellationV2(firstPattern, confirmPattern)  // V2: Use V2 method
            }
            println("VOID_DEBUG: Registration result = $result")

            val currentState = _state.value
            if (currentState !is ConstellationConfirmState.Ready) {
                println("VOID_DEBUG: State changed during validation, returning")
                return@launch
            }

            when (result) {
                is RegistrationResult.Success -> {
                    println("VOID_DEBUG: Registration SUCCESS!")
                    // Store verification hash and algorithm version
                    generationMetadata?.let { metadata ->
                        println("VOID_DEBUG: Storing verification hash: ${metadata.verificationHash}")
                        securityManager.storeVerificationHash(
                            hash = metadata.verificationHash,
                            version = metadata.algorithmVersion
                        )
                    } ?: println("VOID_DEBUG: WARNING - generationMetadata is null!")

                    println("VOID_DEBUG: Setting state to Success")
                    _state.value = ConstellationConfirmState.Success
                }
                is RegistrationResult.MismatchWithConfirmation -> {
                    println("VOID_DEBUG: Patterns don't match!")
                    // Reset taps but show error - keep constellation visible
                    _state.value = currentState.copy(
                        tappedStars = emptyList(),
                        errorMessage = "Patterns don't match. Try again."
                    )
                }
                is RegistrationResult.InvalidQuality -> {
                    println("VOID_DEBUG: Invalid quality!")
                    _state.value = currentState.copy(
                        tappedStars = emptyList(),
                        errorMessage = "Pattern quality too low"
                    )
                }
                is RegistrationResult.PointsTooClose -> {
                    println("VOID_DEBUG: Points too close!")
                    _state.value = currentState.copy(
                        tappedStars = emptyList(),
                        errorMessage = "Stars too close together"
                    )
                }
            }
        }
    }

    fun onReset() {
        val currentState = _state.value
        if (currentState is ConstellationConfirmState.Ready) {
            _state.value = currentState.copy(
                tappedStars = emptyList(),
                errorMessage = null  // Clear error when resetting
            )
        }
    }
}

sealed class ConstellationConfirmState {
    object Loading : ConstellationConfirmState()

    data class Ready(
        val constellation: Bitmap,
        val landmarks: List<StarGenerator.Landmark>,  // V2: Store landmarks
        val firstPattern: LandmarkPattern,  // V2: Use LandmarkPattern
        val tappedStars: List<TapPoint>,
        val errorMessage: String? = null
    ) : ConstellationConfirmState()

    object Success : ConstellationConfirmState()
    data class Error(val message: String) : ConstellationConfirmState()
}
