package app.voidapp.block.constellation.ui.setup

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
 * ViewModel for constellation setup screen.
 * Manages constellation generation and pattern creation.
 */
class ConstellationSetupViewModel(
    private val starGenerator: StarGenerator,
    private val matcher: ConstellationMatcher,
    private val quantizer: StarQuantizer,
    private val getIdentitySeed: suspend () -> ByteArray?
) : ViewModel() {

    private val _state = MutableStateFlow<ConstellationSetupState>(ConstellationSetupState.Loading)
    val state: StateFlow<ConstellationSetupState> = _state.asStateFlow()

    private var generationMetadata: StarGenerator.GenerationMetadata? = null

    init {
        generateConstellation()
    }

    fun generateConstellation(width: Int = 1080, height: Int = 1920) {
        viewModelScope.launch {
            _state.value = ConstellationSetupState.Loading

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
                generationMetadata = result.metadata
                _state.value = ConstellationSetupState.Ready(
                    constellation = result.bitmap,
                    landmarks = result.landmarks,  // V2: Store landmarks
                    tappedStars = emptyList(),
                    requiredStars = ConstellationSecurityManager.MIN_LANDMARKS,  // V2: Use MIN_LANDMARKS
                    patternQuality = 0
                )
            } else {
                _state.value = ConstellationSetupState.Error("Failed to generate constellation")
            }
        }
    }

    fun onStarTapped(tap: TapPoint, screenWidth: Int, screenHeight: Int) {
        val currentState = _state.value
        if (currentState !is ConstellationSetupState.Ready) return

        println("VOID_DEBUG: SetupVM - Tap received at (${tap.x}, ${tap.y}), screen: ${screenWidth}x${screenHeight}")
        println("VOID_DEBUG: SetupVM - Current taps: ${currentState.tappedStars.size}, landmarks available: ${currentState.landmarks.size}")

        val currentTaps = currentState.tappedStars
        if (currentTaps.size >= ConstellationSecurityManager.MAX_LANDMARKS) {
            println("VOID_DEBUG: SetupVM - Max landmarks reached, ignoring tap")
            return
        }

        // Check if tap hits a landmark before adding it
        val hitLandmark = matcher.findNearestLandmark(tap, currentState.landmarks)
        if (hitLandmark == null) {
            println("VOID_DEBUG: SetupVM - Tap missed all landmarks, ignoring")
            return
        }

        val newTaps = currentTaps + tap

        // V2: Convert to landmark pattern using gravity well
        val landmarkIndices = matcher.tapsToLandmarkIndices(newTaps, currentState.landmarks)
        val quality = matcher.calculateQualityV2(landmarkIndices.size)

        println("VOID_DEBUG: SetupVM - Landmark indices: $landmarkIndices (${landmarkIndices.size} landmarks)")

        val canProceed = landmarkIndices.size >= ConstellationSecurityManager.MIN_LANDMARKS && quality >= 50

        _state.value = currentState.copy(
            tappedStars = newTaps,
            patternQuality = quality,
            canProceed = canProceed
        )
    }

    fun onReset() {
        val currentState = _state.value
        if (currentState is ConstellationSetupState.Ready) {
            _state.value = currentState.copy(
                tappedStars = emptyList(),
                patternQuality = 0,
                canProceed = false
            )
        }
    }

    fun onProceed(screenWidth: Int, screenHeight: Int) {
        val currentState = _state.value
        if (currentState !is ConstellationSetupState.Ready) return

        // V2: Create landmark pattern using gravity well
        val landmarkPattern = matcher.createLandmarkPattern(
            currentState.tappedStars,
            currentState.landmarks
        )

        // Include the constellation bitmap and landmarks for confirm screen
        _state.value = ConstellationSetupState.PatternCreated(
            pattern = landmarkPattern,
            metadata = generationMetadata,
            constellation = currentState.constellation,
            landmarks = currentState.landmarks,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    fun getVerificationHash(): String? = generationMetadata?.verificationHash
    fun getAlgorithmVersion(): Int = generationMetadata?.algorithmVersion ?: StarGenerator.ALGORITHM_VERSION
}

sealed class ConstellationSetupState {
    object Loading : ConstellationSetupState()

    data class Ready(
        val constellation: Bitmap,
        val landmarks: List<StarGenerator.Landmark>,  // V2: Store landmarks
        val tappedStars: List<TapPoint>,
        val requiredStars: Int,
        val patternQuality: Int,
        val canProceed: Boolean = false
    ) : ConstellationSetupState()

    data class PatternCreated(
        val pattern: LandmarkPattern,  // V2: Use LandmarkPattern
        val metadata: StarGenerator.GenerationMetadata?,
        val constellation: Bitmap,  // Pass the exact same bitmap to confirm screen
        val landmarks: List<StarGenerator.Landmark>,  // V2: Pass landmarks too
        val screenWidth: Int,       // Lock dimensions for consistency
        val screenHeight: Int
    ) : ConstellationSetupState()

    data class Error(val message: String) : ConstellationSetupState()
}
