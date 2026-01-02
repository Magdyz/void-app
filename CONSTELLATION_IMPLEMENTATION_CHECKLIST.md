# VOID Constellation Lock - Master Implementation Checklist

> **Codebase-Specific Checklist** - Updated based on VOID app architecture analysis
>
> **Architecture**: Slate + Block (Modular MVI)
> **DI Framework**: Koin
> **Reference Implementation**: RhythmBlock (`blocks/rhythm/`)
> **Security Stack**: KeystoreManager + SecureStorage (SQLCipher) + CryptoProvider (Tink)

---

## Prerequisites Verification

- [x] KeystoreManager exists at `slate/crypto/src/main/kotlin/com/void/slate/crypto/keystore/KeystoreManager.kt`
- [x] SecureStorage exists at `slate/storage/src/main/kotlin/com/void/slate/storage/SecureStorage.kt`
- [x] CryptoProvider exists at `slate/core/src/main/kotlin/com/void/slate/crypto/CryptoProvider.kt`
- [x] IdentityRepository exists at `blocks/identity/src/main/kotlin/com/void/block/identity/data/IdentityRepository.kt`
- [x] RhythmBlock exists as reference at `blocks/rhythm/`
- [x] EventBus exists for cross-block communication
- [x] MviViewModel base class exists for state management
- [x] BIP-39 support exists at `slate/crypto/src/main/kotlin/com/void/slate/crypto/wordlist/BIP39.kt`

---

## Phase 1: Project Setup

### 1.1 Create Block Structure
- [ ] Create directory: `blocks/constellation/`
- [ ] Create `blocks/constellation/build.gradle.kts`:
  ```kotlin
  plugins {
      id("void.block")
      alias(libs.plugins.kotlin.serialization)
  }

  android {
      namespace = "app.voidapp.block.constellation"
  }

  dependencies {
      implementation(project(":slate:core"))
      implementation(project(":slate:crypto"))
      implementation(project(":slate:storage"))
      implementation(project(":slate:design"))
      implementation(project(":blocks:identity"))

      implementation(libs.kotlinx.serialization.json)
      implementation(libs.androidx.compose.material3)
      implementation(libs.koin.androidx.compose)
  }
  ```

- [ ] Add to `settings.gradle.kts`:
  ```kotlin
  include(":blocks:constellation")
  ```

- [ ] Create package structure:
  ```
  blocks/constellation/src/main/kotlin/app/voidapp/block/constellation/
  ├── ConstellationBlock.kt
  ├── ui/
  │   ├── setup/
  │   │   ├── ConstellationSetupScreen.kt
  │   │   ├── ConstellationSetupViewModel.kt
  │   │   ├── ConstellationSetupIntent.kt
  │   │   └── ConstellationSetupState.kt
  │   ├── confirm/
  │   │   ├── ConstellationConfirmScreen.kt
  │   │   ├── ConstellationConfirmViewModel.kt
  │   │   ├── ConstellationConfirmIntent.kt
  │   │   └── ConstellationConfirmState.kt
  │   ├── unlock/
  │   │   ├── ConstellationUnlockScreen.kt
  │   │   ├── ConstellationUnlockViewModel.kt
  │   │   ├── ConstellationUnlockIntent.kt
  │   │   └── ConstellationUnlockState.kt
  │   └── components/
  │       ├── StarCanvas.kt
  │       ├── ConstellationView.kt
  │       └── PatternQualityIndicator.kt
  ├── domain/
  │   ├── ConstellationModels.kt
  │   ├── StarGenerator.kt
  │   ├── StarQuantizer.kt
  │   └── ConstellationMatcher.kt
  ├── security/
  │   └── ConstellationSecurityManager.kt
  ├── events/
  │   └── ConstellationEvents.kt
  └── data/
      └── ConstellationRepository.kt
  ```

### 1.2 Register Block in App Module
- [ ] Add to `app/src/main/kotlin/com/void/app/di/AppModule.kt`:
  ```kotlin
  import app.voidapp.block.constellation.ConstellationBlock

  // In includes list
  includes(ConstellationBlock().module)
  ```

### 1.3 Add Navigation Routes
- [ ] Add routes to `slate/core/../navigation/Routes.kt`:
  ```kotlin
  object Routes {
      // ... existing routes

      // Constellation routes
      const val CONSTELLATION_SETUP = "constellation/setup"
      const val CONSTELLATION_CONFIRM = "constellation/confirm"
      const val CONSTELLATION_UNLOCK = "constellation/unlock"
      const val CONSTELLATION_RECOVERY = "constellation/recovery"
  }
  ```

---

## Phase 2: Domain Layer

### 2.1 Create Data Models (ConstellationModels.kt)
- [ ] Create `domain/ConstellationModels.kt`:
  ```kotlin
  @Serializable
  data class StarPoint(
      val normalizedX: Float,
      val normalizedY: Float
  )

  @Serializable
  data class ConstellationPattern(
      val stars: List<StarPoint>,
      val quality: Int,
      val createdAt: Long = System.currentTimeMillis()
  )

  @Serializable
  data class QuantizedPoint(
      val gridX: Int,
      val gridY: Int
  )

  data class TapPoint(
      val x: Float,
      val y: Float,
      val timestamp: Long = System.currentTimeMillis()
  )

  sealed class ConstellationResult {
      data class Success(val seedHash: ByteArray) : ConstellationResult()
      data class Failure(val attemptsRemaining: Int) : ConstellationResult()
      object LockedOut : ConstellationResult()
      object InvalidPattern : ConstellationResult()
  }

  sealed class RegistrationResult {
      object Success : RegistrationResult()
      data class InvalidQuality(val quality: Int, val minRequired: Int) : RegistrationResult()
      data class PointsTooClose(val minDistance: Float) : RegistrationResult()
      object MismatchWithConfirmation : RegistrationResult()
  }
  ```

### 2.2 Create StarGenerator (Deterministic Pattern Generation)
- [ ] Create `domain/StarGenerator.kt`:
  ```kotlin
  import android.graphics.Bitmap
  import android.graphics.Canvas
  import android.graphics.Color
  import android.graphics.Paint
  import android.graphics.Path
  import java.security.MessageDigest
  import kotlin.random.Random

  class StarGenerator(
      private val crypto: CryptoProvider
  ) {
      companion object {
          const val ALGORITHM_VERSION = 1
          const val NODE_COUNT = 50
          const val CONNECTION_DENSITY = 0.15f
      }

      data class GenerationMetadata(
          val algorithmVersion: Int = ALGORITHM_VERSION,
          val verificationHash: String,
          val generatedAt: Long = System.currentTimeMillis()
      )

      suspend fun generate(
          identitySeed: ByteArray,
          width: Int,
          height: Int
      ): Pair<Bitmap, GenerationMetadata> {
          // Derive deterministic seed from identity
          val constellationSeed = crypto.derive(identitySeed, "m/constellation/0")
          val seedLong = deriveSeedLong(constellationSeed)
          val random = Random(seedLong)

          val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
          val canvas = Canvas(bitmap)

          // Dark background
          canvas.drawColor(Color.parseColor("#0a0a0f"))

          // Generate normalized node positions (0.0-1.0 for device independence)
          val normalizedNodes = List(NODE_COUNT) {
              Pair(random.nextFloat(), random.nextFloat())
          }

          val nodes = normalizedNodes.map { (nx, ny) ->
              Pair(nx * width, ny * height)
          }

          // Draw connections
          drawConnections(canvas, nodes, random, width)

          // Draw nodes
          drawNodes(canvas, nodes, random, constellationSeed, width)

          // Draw landmarks
          drawLandmarks(canvas, random, width, height)

          // Generate verification hash
          val verificationHash = generateVerificationHash(constellationSeed, normalizedNodes)

          val metadata = GenerationMetadata(
              verificationHash = verificationHash
          )

          return Pair(bitmap, metadata)
      }

      private fun deriveSeedLong(seed: ByteArray): Long {
          var result = 0L
          for (i in 0 until minOf(8, seed.size)) {
              result = result or ((seed[i].toLong() and 0xFF) shl (i * 8))
          }
          return result
      }

      private suspend fun generateVerificationHash(
          seed: ByteArray,
          nodes: List<Pair<Float, Float>>
      ): String {
          val input = buildString {
              append("v$ALGORITHM_VERSION:")
              append(seed.take(16).joinToString("") { "%02x".format(it) })
              append(":")
              nodes.take(10).forEach { (x, y) ->
                  append("%.4f,%.4f;".format(x, y))
              }
          }
          val hash = crypto.hash(input.toByteArray())
          return hash.take(16).joinToString("") { "%02x".format(it) }
      }

      private fun drawConnections(
          canvas: Canvas,
          nodes: List<Pair<Float, Float>>,
          random: Random,
          width: Int
      ) {
          val linePaint = Paint().apply {
              color = Color.parseColor("#1a1a2e")
              strokeWidth = 1.5f * (width / 1080f)
              style = Paint.Style.STROKE
              alpha = 60
              isAntiAlias = true
          }

          nodes.forEachIndexed { i, _ ->
              nodes.drop(i + 1).forEachIndexed { j, _ ->
                  if (random.nextFloat() < CONNECTION_DENSITY) {
                      canvas.drawLine(
                          nodes[i].first, nodes[i].second,
                          nodes[i + j + 1].first, nodes[i + j + 1].second,
                          linePaint
                      )
                  }
              }
          }
      }

      private fun drawNodes(
          canvas: Canvas,
          nodes: List<Pair<Float, Float>>,
          random: Random,
          seed: ByteArray,
          width: Int
      ) {
          val nodePaint = Paint().apply {
              style = Paint.Style.FILL
              isAntiAlias = true
          }

          val hueBase = (seed[0].toInt() and 0xFF) / 255f * 360f
          val colors = listOf(
              Color.HSVToColor(floatArrayOf(hueBase, 0.6f, 0.8f)),
              Color.HSVToColor(floatArrayOf((hueBase + 30) % 360, 0.5f, 0.9f)),
              Color.HSVToColor(floatArrayOf((hueBase + 60) % 360, 0.4f, 0.7f))
          )

          nodes.forEachIndexed { i, (x, y) ->
              nodePaint.color = colors[i % colors.size]
              nodePaint.alpha = 100 + random.nextInt(155)
              val baseRadius = 3f + random.nextFloat() * 8f
              val scaledRadius = baseRadius * (width / 1080f)
              canvas.drawCircle(x, y, scaledRadius, nodePaint)
          }
      }

      private fun drawLandmarks(
          canvas: Canvas,
          random: Random,
          width: Int,
          height: Int
      ) {
          val scale = width / 1080f
          val landmarkPaint = Paint().apply {
              style = Paint.Style.STROKE
              strokeWidth = 2f * scale
              alpha = 40
              color = Color.WHITE
              isAntiAlias = true
          }

          val landmarkCount = 8 + random.nextInt(5)
          repeat(landmarkCount) {
              val cx = random.nextFloat() * width
              val cy = random.nextFloat() * height
              val size = (20f + random.nextFloat() * 40f) * scale

              when (random.nextInt(4)) {
                  0 -> canvas.drawCircle(cx, cy, size, landmarkPaint)
                  1 -> canvas.drawRect(
                      cx - size, cy - size, cx + size, cy + size,
                      landmarkPaint
                  )
                  2 -> {
                      val path = Path().apply {
                          moveTo(cx, cy - size)
                          lineTo(cx + size, cy + size)
                          lineTo(cx - size, cy + size)
                          close()
                      }
                      canvas.drawPath(path, landmarkPaint)
                  }
                  3 -> {
                      val path = Path().apply {
                          moveTo(cx, cy - size)
                          lineTo(cx + size, cy)
                          lineTo(cx, cy + size)
                          lineTo(cx - size, cy)
                          close()
                      }
                      canvas.drawPath(path, landmarkPaint)
                  }
              }
          }
      }
  }
  ```

### 2.3 Create StarQuantizer (Grid-Based Quantization)
- [ ] Create `domain/StarQuantizer.kt`:
  ```kotlin
  class StarQuantizer {
      companion object {
          const val GRID_SIZE = 64
      }

      fun quantize(point: StarPoint): QuantizedPoint {
          val cellX = (point.normalizedX * GRID_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
          val cellY = (point.normalizedY * GRID_SIZE).toInt().coerceIn(0, GRID_SIZE - 1)
          return QuantizedPoint(cellX, cellY)
      }

      fun normalize(tap: TapPoint, screenWidth: Int, screenHeight: Int): StarPoint {
          return StarPoint(
              normalizedX = tap.x / screenWidth,
              normalizedY = tap.y / screenHeight
          )
      }
  }
  ```

### 2.4 Create ConstellationMatcher (Pattern Verification)
- [ ] Create `domain/ConstellationMatcher.kt`:
  ```kotlin
  class ConstellationMatcher(
      private val quantizer: StarQuantizer
  ) {
      companion object {
          const val MIN_POINT_DISTANCE = 0.10f  // 10% of screen
      }

      /**
       * Match using fixed grid quantization (order matters).
       * This is constant-time to prevent timing attacks.
       */
      fun matches(
          attempt: ConstellationPattern,
          stored: ConstellationPattern
      ): Boolean {
          if (attempt.stars.size != stored.stars.size) return false

          val attemptQuantized = attempt.stars.map { quantizer.quantize(it) }
          val storedQuantized = stored.stars.map { quantizer.quantize(it) }

          // Constant-time comparison
          var match = true
          attemptQuantized.zip(storedQuantized).forEach { (a, s) ->
              if (a.gridX != s.gridX || a.gridY != s.gridY) {
                  match = false
              }
          }

          return match
      }

      /**
       * Validate pattern quality.
       */
      fun validatePattern(pattern: ConstellationPattern): RegistrationResult {
          val stars = pattern.stars

          // Check point spacing
          for (i in stars.indices) {
              for (j in i + 1 until stars.size) {
                  val distance = calculateDistance(stars[i], stars[j])
                  if (distance < MIN_POINT_DISTANCE) {
                      return RegistrationResult.PointsTooClose(MIN_POINT_DISTANCE)
                  }
              }
          }

          // Check quality score
          val quality = calculateQuality(pattern)
          if (quality < 50) {
              return RegistrationResult.InvalidQuality(quality, 50)
          }

          return RegistrationResult.Success
      }

      fun calculateQuality(pattern: ConstellationPattern): Int {
          val stars = pattern.stars
          var score = 100

          // Penalize if all stars in same quadrant
          val quadrants = stars.map { star ->
              val qx = if (star.normalizedX < 0.5f) 0 else 1
              val qy = if (star.normalizedY < 0.5f) 0 else 2
              qx + qy
          }.toSet()

          when (quadrants.size) {
              1 -> score -= 40
              2 -> score -= 20
          }

          // Penalize if stars too close together
          val minDistance = stars.indices.flatMap { i ->
              stars.indices.drop(i + 1).map { j ->
                  calculateDistance(stars[i], stars[j])
              }
          }.minOrNull() ?: 0f

          when {
              minDistance < 0.1f -> score -= 30
              minDistance < 0.15f -> score -= 15
          }

          // Penalize linear patterns
          val xSpread = stars.maxOf { it.normalizedX } - stars.minOf { it.normalizedX }
          val ySpread = stars.maxOf { it.normalizedY } - stars.minOf { it.normalizedY }

          if (xSpread < 0.2f || ySpread < 0.2f) score -= 25

          return score.coerceIn(0, 100)
      }

      private fun calculateDistance(a: StarPoint, b: StarPoint): Float {
          val dx = a.normalizedX - b.normalizedX
          val dy = a.normalizedY - b.normalizedY
          return kotlin.math.sqrt(dx * dx + dy * dy)
      }
  }
  ```

---

## Phase 3: Security Layer (Follow RhythmSecurityManager Pattern)

### 3.1 Create ConstellationSecurityManager
- [ ] Create `security/ConstellationSecurityManager.kt`:
  ```kotlin
  import app.voidapp.block.identity.data.IdentityRepository
  import com.void.slate.crypto.CryptoProvider
  import com.void.slate.crypto.keystore.KeystoreManager
  import com.void.slate.storage.SecureStorage
  import kotlinx.coroutines.sync.Mutex
  import kotlinx.coroutines.sync.withLock
  import kotlinx.serialization.encodeToString
  import kotlinx.serialization.json.Json

  class ConstellationSecurityManager(
      private val keystoreManager: KeystoreManager,
      private val storage: SecureStorage,
      private val crypto: CryptoProvider,
      private val matcher: ConstellationMatcher,
      private val identityRepository: IdentityRepository
  ) {
      companion object {
          private const val KEY_ALIAS = "constellation_master_key"

          // Storage keys
          private const val KEY_REAL_PATTERN_ENCRYPTED = "constellation.pattern.real.encrypted"
          private const val KEY_DECOY_PATTERN_ENCRYPTED = "constellation.pattern.decoy.encrypted"
          private const val KEY_VERIFICATION_HASH = "constellation.verification_hash"
          private const val KEY_ALGORITHM_VERSION = "constellation.algorithm_version"
          private const val KEY_FAILED_ATTEMPTS = "constellation.failed_attempts"
          private const val KEY_LOCKOUT_END_TIME = "constellation.lockout_end_time"
          private const val KEY_TOTAL_ATTEMPTS = "constellation.total_attempts"

          // Security constants
          const val MIN_STARS = 6
          const val MAX_STARS = 8
          const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
          const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L  // 5 minutes
          const val MAX_TOTAL_ATTEMPTS_BEFORE_WIPE = 20
      }

      private val mutex = Mutex()

      /**
       * Check if constellation is set up.
       */
      suspend fun hasRealConstellation(): Boolean = mutex.withLock {
          storage.contains(KEY_REAL_PATTERN_ENCRYPTED)
      }

      /**
       * Check if setup is complete (includes verification hash).
       */
      suspend fun isSetupComplete(): Boolean = mutex.withLock {
          hasRealConstellation() && storage.contains(KEY_VERIFICATION_HASH)
      }

      /**
       * Register the real constellation pattern.
       */
      suspend fun registerRealConstellation(
          pattern: ConstellationPattern,
          confirmPattern: ConstellationPattern
      ): RegistrationResult = mutex.withLock {
          // Validate individual pattern
          val validationResult = matcher.validatePattern(pattern)
          if (validationResult !is RegistrationResult.Success) {
              return@withLock validationResult
          }

          // Verify confirmation matches
          if (!matcher.matches(pattern, confirmPattern)) {
              return@withLock RegistrationResult.MismatchWithConfirmation
          }

          // Ensure Keystore key exists
          if (!keystoreManager.hasKey(KEY_ALIAS)) {
              keystoreManager.generateKey(
                  alias = KEY_ALIAS,
                  requireAuth = false,
                  useStrongBox = true
              )
          }

          // Serialize and encrypt pattern
          val patternJson = Json.encodeToString(pattern)
          val encrypted = keystoreManager.encrypt(KEY_ALIAS, patternJson.toByteArray())

          // Store encrypted pattern
          storage.put(KEY_REAL_PATTERN_ENCRYPTED, encrypted)

          RegistrationResult.Success
      }

      /**
       * Store verification hash after constellation is generated.
       */
      suspend fun storeVerificationHash(hash: String, version: Int) = mutex.withLock {
          storage.putString(KEY_VERIFICATION_HASH, hash)
          storage.putString(KEY_ALGORITHM_VERSION, version.toString())
      }

      /**
       * Verify constellation integrity.
       */
      suspend fun verifyConstellationIntegrity(currentHash: String): Boolean = mutex.withLock {
          val storedHash = storage.getString(KEY_VERIFICATION_HASH) ?: return@withLock false
          storedHash == currentHash
      }

      /**
       * Attempt to unlock with constellation pattern.
       */
      suspend fun unlock(attempt: ConstellationPattern): ConstellationResult = mutex.withLock {
          // Check lockout
          val lockoutEnd = storage.getString(KEY_LOCKOUT_END_TIME)?.toLongOrNull() ?: 0L
          if (System.currentTimeMillis() < lockoutEnd) {
              return@withLock ConstellationResult.LockedOut
          }

          // Load stored pattern
          val encryptedPattern = storage.get(KEY_REAL_PATTERN_ENCRYPTED)
              ?: return@withLock ConstellationResult.InvalidPattern

          val storedPattern = try {
              val decrypted = keystoreManager.decrypt(KEY_ALIAS, encryptedPattern)
              Json.decodeFromString<ConstellationPattern>(String(decrypted))
          } catch (e: Exception) {
              return@withLock ConstellationResult.InvalidPattern
          }

          // Verify pattern (constant-time)
          if (matcher.matches(attempt, storedPattern)) {
              // Success - reset counters
              resetFailedAttempts()

              // Get identity seed hash for session
              val identity = identityRepository.getIdentity()
              val seedHash = identity?.let { crypto.hash(it.seed) }
                  ?: return@withLock ConstellationResult.InvalidPattern

              return@withLock ConstellationResult.Success(seedHash)
          }

          // Failure - increment counters
          val failedAttempts = incrementFailedAttempts()
          val totalAttempts = incrementTotalAttempts()

          // Check for wipe threshold
          if (totalAttempts >= MAX_TOTAL_ATTEMPTS_BEFORE_WIPE) {
              panicWipe()
              return@withLock ConstellationResult.LockedOut
          }

          // Check for lockout
          if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
              val lockoutEndTime = System.currentTimeMillis() + LOCKOUT_DURATION_MS
              storage.putString(KEY_LOCKOUT_END_TIME, lockoutEndTime.toString())
              return@withLock ConstellationResult.LockedOut
          }

          ConstellationResult.Failure(MAX_ATTEMPTS_BEFORE_LOCKOUT - failedAttempts)
      }

      /**
       * Recover using BIP-39 mnemonic phrase.
       */
      suspend fun recoverFromPhrase(mnemonic: List<String>): Boolean = mutex.withLock {
          // Validate mnemonic matches stored identity
          // This would integrate with IdentityRepository
          // For now, simplified:

          // Clear constellation pattern
          storage.delete(KEY_REAL_PATTERN_ENCRYPTED)
          storage.delete(KEY_DECOY_PATTERN_ENCRYPTED)

          // Reset security state
          resetFailedAttempts()
          resetTotalAttempts()
          storage.delete(KEY_LOCKOUT_END_TIME)

          // Keep verification hash for integrity check

          true
      }

      /**
       * Emergency wipe (called after max total attempts).
       */
      suspend fun panicWipe() = mutex.withLock {
          // Wipe all constellation data
          storage.delete(KEY_REAL_PATTERN_ENCRYPTED)
          storage.delete(KEY_DECOY_PATTERN_ENCRYPTED)
          storage.delete(KEY_VERIFICATION_HASH)
          storage.delete(KEY_ALGORITHM_VERSION)
          resetFailedAttempts()
          resetTotalAttempts()
          storage.delete(KEY_LOCKOUT_END_TIME)

          // Delete Keystore key
          keystoreManager.deleteKey(KEY_ALIAS)

          // Note: Identity wipe would be coordinated via EventBus
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
  }
  ```

---

## Phase 4: UI Layer (MVI Pattern)

### 4.1 Setup Screen - State & Intent
- [ ] Create `ui/setup/ConstellationSetupState.kt`:
  ```kotlin
  import android.graphics.Bitmap

  data class ConstellationSetupState(
      val constellation: Bitmap? = null,
      val tappedStars: List<TapPoint> = emptyList(),
      val requiredStars: Int = ConstellationSecurityManager.MIN_STARS,
      val patternQuality: Int = 0,
      val isLoading: Boolean = true,
      val error: String? = null,
      val canProceed: Boolean = false
  )

  sealed class ConstellationSetupIntent {
      data class OnStarTapped(val tap: TapPoint, val screenWidth: Int, val screenHeight: Int) : ConstellationSetupIntent()
      object OnResetPattern : ConstellationSetupIntent()
      object OnProceedToConfirmation : ConstellationSetupIntent()
  }

  sealed class ConstellationSetupEffect {
      data class NavigateToConfirmation(val pattern: ConstellationPattern) : ConstellationSetupEffect()
      data class ShowError(val message: String) : ConstellationSetupEffect()
  }
  ```

### 4.2 Setup Screen - ViewModel
- [ ] Create `ui/setup/ConstellationSetupViewModel.kt`:
  ```kotlin
  import androidx.lifecycle.viewModelScope
  import com.void.slate.core.mvi.MviViewModel
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext

  class ConstellationSetupViewModel(
      private val starGenerator: StarGenerator,
      private val matcher: ConstellationMatcher,
      private val quantizer: StarQuantizer,
      private val identityRepository: IdentityRepository
  ) : MviViewModel<ConstellationSetupState, ConstellationSetupIntent, ConstellationSetupEffect>(
      initialState = ConstellationSetupState()
  ) {

      init {
          generateConstellation()
      }

      override fun handleIntent(intent: ConstellationSetupIntent) {
          when (intent) {
              is ConstellationSetupIntent.OnStarTapped -> handleStarTapped(intent)
              is ConstellationSetupIntent.OnResetPattern -> handleReset()
              is ConstellationSetupIntent.OnProceedToConfirmation -> handleProceed()
          }
      }

      private fun generateConstellation() {
          viewModelScope.launch {
              setState { copy(isLoading = true) }

              val result = withContext(Dispatchers.Default) {
                  val identity = identityRepository.getIdentity()
                      ?: return@withContext null

                  // Get screen dimensions from state or use default
                  // This would be passed in from Composable
                  starGenerator.generate(identity.seed, 1080, 1920)
              }

              if (result != null) {
                  val (bitmap, _) = result
                  setState {
                      copy(
                          constellation = bitmap,
                          isLoading = false
                      )
                  }
              } else {
                  setState {
                      copy(
                          isLoading = false,
                          error = "Failed to generate constellation"
                      )
                  }
              }
          }
      }

      private fun handleStarTapped(intent: ConstellationSetupIntent.OnStarTapped) {
          val currentStars = state.value.tappedStars

          if (currentStars.size >= ConstellationSecurityManager.MAX_STARS) {
              return
          }

          val newStars = currentStars + intent.tap

          // Convert to pattern
          val pattern = ConstellationPattern(
              stars = newStars.map {
                  quantizer.normalize(it, intent.screenWidth, intent.screenHeight)
              },
              quality = 0
          )

          // Calculate quality
          val quality = matcher.calculateQuality(pattern)

          setState {
              copy(
                  tappedStars = newStars,
                  patternQuality = quality,
                  canProceed = newStars.size >= ConstellationSecurityManager.MIN_STARS && quality >= 50
              )
          }
      }

      private fun handleReset() {
          setState {
              copy(
                  tappedStars = emptyList(),
                  patternQuality = 0,
                  canProceed = false
              )
          }
      }

      private fun handleProceed() {
          val stars = state.value.tappedStars
          if (stars.size < ConstellationSecurityManager.MIN_STARS) {
              return
          }

          // This would need screen dimensions - simplified
          val pattern = ConstellationPattern(
              stars = stars.map { StarPoint(it.x, it.y) },
              quality = state.value.patternQuality
          )

          setEffect { ConstellationSetupEffect.NavigateToConfirmation(pattern) }
      }
  }
  ```

### 4.3 Setup Screen - Composable
- [ ] Create `ui/setup/ConstellationSetupScreen.kt`:
  ```kotlin
  import androidx.compose.foundation.Image
  import androidx.compose.foundation.layout.*
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.graphics.asImageBitmap
  import androidx.compose.ui.unit.dp
  import app.voidapp.block.constellation.ui.components.ConstellationView
  import app.voidapp.block.constellation.ui.components.PatternQualityIndicator
  import com.void.slate.navigation.Navigator
  import org.koin.androidx.compose.koinViewModel

  @Composable
  fun ConstellationSetupScreen(
      navigator: Navigator,
      viewModel: ConstellationSetupViewModel = koinViewModel()
  ) {
      val state by viewModel.state.collectAsState()

      LaunchedEffect(Unit) {
          viewModel.effects.collect { effect ->
              when (effect) {
                  is ConstellationSetupEffect.NavigateToConfirmation -> {
                      // Navigate with pattern as argument
                      navigator.navigate(Routes.CONSTELLATION_CONFIRM)
                  }
                  is ConstellationSetupEffect.ShowError -> {
                      // Show snackbar
                  }
              }
          }
      }

      ConstellationSetupContent(
          state = state,
          onStarTapped = { tap, width, height ->
              viewModel.handleIntent(
                  ConstellationSetupIntent.OnStarTapped(tap, width, height)
              )
          },
          onReset = {
              viewModel.handleIntent(ConstellationSetupIntent.OnResetPattern)
          },
          onProceed = {
              viewModel.handleIntent(ConstellationSetupIntent.OnProceedToConfirmation)
          }
      )
  }

  @Composable
  private fun ConstellationSetupContent(
      state: ConstellationSetupState,
      onStarTapped: (TapPoint, Int, Int) -> Unit,
      onReset: () -> Unit,
      onProceed: () -> Unit
  ) {
      if (state.isLoading) {
          Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center
          ) {
              CircularProgressIndicator()
          }
          return
      }

      Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally
      ) {
          // Header
          Text(
              text = "Create Your Constellation Pattern",
              style = MaterialTheme.typography.headlineMedium,
              modifier = Modifier.padding(24.dp)
          )

          Text(
              text = "Tap ${state.requiredStars}-${ConstellationSecurityManager.MAX_STARS} stars in sequence",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(bottom = 16.dp)
          )

          // Constellation view
          state.constellation?.let { bitmap ->
              ConstellationView(
                  constellation = bitmap,
                  tappedPoints = state.tappedStars,
                  onTap = onStarTapped,
                  modifier = Modifier.weight(1f)
              )
          }

          // Quality indicator
          if (state.tappedStars.isNotEmpty()) {
              PatternQualityIndicator(
                  quality = state.patternQuality,
                  modifier = Modifier.padding(16.dp)
              )
          }

          // Actions
          Row(
              modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
              horizontalArrangement = Arrangement.SpaceBetween
          ) {
              OutlinedButton(onClick = onReset) {
                  Text("Reset")
              }

              Button(
                  onClick = onProceed,
                  enabled = state.canProceed
              ) {
                  Text("Continue")
              }
          }
      }
  }
  ```

### 4.4 Create ConstellationView Component
- [ ] Create `ui/components/ConstellationView.kt`:
  ```kotlin
  import android.app.Activity
  import android.view.WindowManager
  import androidx.compose.foundation.Canvas
  import androidx.compose.foundation.Image
  import androidx.compose.foundation.gestures.detectTapGestures
  import androidx.compose.foundation.layout.Box
  import androidx.compose.foundation.layout.fillMaxSize
  import androidx.compose.runtime.*
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.geometry.Offset
  import androidx.compose.ui.graphics.Color
  import androidx.compose.ui.graphics.asImageBitmap
  import androidx.compose.ui.graphics.drawscope.Stroke
  import androidx.compose.ui.hapticfeedback.HapticFeedbackType
  import androidx.compose.ui.input.pointer.pointerInput
  import androidx.compose.ui.layout.ContentScale
  import androidx.compose.ui.layout.onSizeChanged
  import androidx.compose.ui.platform.LocalContext
  import androidx.compose.ui.platform.LocalHapticFeedback
  import androidx.compose.ui.unit.IntSize
  import android.graphics.Bitmap

  @Composable
  fun ConstellationView(
      constellation: Bitmap,
      tappedPoints: List<TapPoint>,
      onTap: (TapPoint, Int, Int) -> Unit,
      modifier: Modifier = Modifier,
      privacyMode: Boolean = false
  ) {
      val context = LocalContext.current
      val haptic = LocalHapticFeedback.current
      var screenSize by remember { mutableStateOf(IntSize.Zero) }

      // FLAG_SECURE to prevent screenshots
      DisposableEffect(Unit) {
          val window = (context as? Activity)?.window
          window?.setFlags(
              WindowManager.LayoutParams.FLAG_SECURE,
              WindowManager.LayoutParams.FLAG_SECURE
          )
          onDispose {
              window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
          }
      }

      Box(
          modifier = modifier
              .fillMaxSize()
              .onSizeChanged { screenSize = it }
              .pointerInput(Unit) {
                  detectTapGestures { offset ->
                      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                      val tap = TapPoint(offset.x, offset.y)
                      onTap(tap, screenSize.width, screenSize.height)
                  }
              }
      ) {
          // Background constellation
          Image(
              bitmap = constellation.asImageBitmap(),
              contentDescription = "Constellation pattern",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop
          )

          // Draw tap indicators (unless privacy mode)
          if (!privacyMode) {
              Canvas(modifier = Modifier.fillMaxSize()) {
                  tappedPoints.forEachIndexed { index, point ->
                      // Inner dot
                      drawCircle(
                          color = Color.White.copy(alpha = 0.7f),
                          radius = 10f,
                          center = Offset(point.x, point.y)
                      )
                      // Outer ring
                      drawCircle(
                          color = Color.White.copy(alpha = 0.3f),
                          radius = 22f,
                          center = Offset(point.x, point.y),
                          style = Stroke(width = 2f)
                      )
                  }
              }
          }
      }
  }
  ```

### 4.5 Create PatternQualityIndicator Component
- [ ] Create `ui/components/PatternQualityIndicator.kt`:
  ```kotlin
  import androidx.compose.foundation.layout.Column
  import androidx.compose.foundation.layout.fillMaxWidth
  import androidx.compose.material3.LinearProgressIndicator
  import androidx.compose.material3.MaterialTheme
  import androidx.compose.material3.Text
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.graphics.Color

  @Composable
  fun PatternQualityIndicator(
      quality: Int,
      modifier: Modifier = Modifier
  ) {
      Column(modifier = modifier) {
          LinearProgressIndicator(
              progress = quality / 100f,
              color = when {
                  quality < 50 -> Color.Red
                  quality < 75 -> Color.Yellow
                  else -> Color.Green
              },
              modifier = Modifier.fillMaxWidth()
          )

          Text(
              text = when {
                  quality < 50 -> "Weak pattern - spread stars more"
                  quality < 75 -> "Good pattern"
                  else -> "Strong pattern"
              },
              style = MaterialTheme.typography.bodySmall,
              color = when {
                  quality < 50 -> Color.Red
                  quality < 75 -> Color.Yellow
                  else -> Color.Green
              }
          )
      }
  }
  ```

### 4.6 Confirm Screen (Similar Pattern to Setup)
- [ ] Create `ui/confirm/ConstellationConfirmState.kt`
- [ ] Create `ui/confirm/ConstellationConfirmViewModel.kt`
- [ ] Create `ui/confirm/ConstellationConfirmScreen.kt`

### 4.7 Unlock Screen (Similar Pattern to Setup)
- [ ] Create `ui/unlock/ConstellationUnlockState.kt`
- [ ] Create `ui/unlock/ConstellationUnlockViewModel.kt`
- [ ] Create `ui/unlock/ConstellationUnlockScreen.kt`

---

## Phase 5: Block Integration

### 5.1 Create ConstellationBlock Manifest
- [ ] Create `ConstellationBlock.kt`:
  ```kotlin
  import app.voidapp.block.constellation.ui.setup.ConstellationSetupScreen
  import app.voidapp.block.constellation.ui.confirm.ConstellationConfirmScreen
  import app.voidapp.block.constellation.ui.unlock.ConstellationUnlockScreen
  import com.void.slate.block.Block
  import com.void.slate.block.BlockManifest
  import com.void.slate.block.Route
  import com.void.slate.navigation.Navigator
  import androidx.compose.runtime.Composable
  import androidx.navigation.NavGraphBuilder
  import androidx.navigation.compose.composable
  import org.koin.androidx.viewmodel.dsl.viewModel
  import org.koin.core.module.Module
  import org.koin.dsl.module

  @Block(id = "constellation", enabledByDefault = true)
  class ConstellationBlock : BlockManifest {
      override val id: String = "constellation"

      override val routes: List<Route> = listOf(
          Route.Screen(Routes.CONSTELLATION_SETUP, "Set Up Constellation"),
          Route.Screen(Routes.CONSTELLATION_CONFIRM, "Confirm Pattern"),
          Route.Screen(Routes.CONSTELLATION_UNLOCK, "Unlock"),
          Route.Screen(Routes.CONSTELLATION_RECOVERY, "Recover Pattern")
      )

      override val module: Module = module {
          // Domain
          single { StarQuantizer() }
          single { ConstellationMatcher(get()) }
          single { StarGenerator(get()) }

          // Security
          single {
              ConstellationSecurityManager(
                  keystoreManager = get(),
                  storage = get(),
                  crypto = get(),
                  matcher = get(),
                  identityRepository = get()
              )
          }

          // ViewModels
          viewModel {
              ConstellationSetupViewModel(
                  starGenerator = get(),
                  matcher = get(),
                  quantizer = get(),
                  identityRepository = get()
              )
          }

          viewModel {
              ConstellationConfirmViewModel(
                  securityManager = get(),
                  starGenerator = get()
              )
          }

          viewModel {
              ConstellationUnlockViewModel(
                  securityManager = get(),
                  starGenerator = get(),
                  identityRepository = get()
              )
          }
      }

      @Composable
      override fun NavGraphBuilder.routes(navigator: Navigator) {
          composable(Routes.CONSTELLATION_SETUP) {
              ConstellationSetupScreen(navigator = navigator)
          }

          composable(Routes.CONSTELLATION_CONFIRM) {
              ConstellationConfirmScreen(navigator = navigator)
          }

          composable(Routes.CONSTELLATION_UNLOCK) {
              ConstellationUnlockScreen(navigator = navigator)
          }
      }
  }
  ```

### 5.2 Create Events
- [ ] Create `events/ConstellationEvents.kt`:
  ```kotlin
  import com.void.slate.event.Event

  sealed class ConstellationEvent : Event {
      data class SetupCompleted(val timestamp: Long) : ConstellationEvent()
      data class UnlockSuccessful(val timestamp: Long) : ConstellationEvent()
      data class UnlockFailed(val attemptsRemaining: Int) : ConstellationEvent()
      object LockedOut : ConstellationEvent()
      object PanicWipeTriggered : ConstellationEvent()
      data class RecoveryInitiated(val timestamp: Long) : ConstellationEvent()
  }
  ```

---

## Phase 6: Testing

### 6.1 Unit Tests - Domain Layer
- [ ] Create `test/domain/StarQuantizerTest.kt`:
  - [ ] Test grid quantization boundary cases (0.0, 0.999, 0.5)
  - [ ] Test normalization round-trip
  - [ ] Test grid cell clamping

- [ ] Create `test/domain/ConstellationMatcherTest.kt`:
  - [ ] Test exact match returns true
  - [ ] Test different patterns return false
  - [ ] Test pattern quality scoring
  - [ ] Test point distance validation

- [ ] Create `test/domain/StarGeneratorTest.kt`:
  - [ ] Test deterministic generation (same seed → same output)
  - [ ] Test different seeds → different outputs
  - [ ] Test verification hash consistency

### 6.2 Unit Tests - Security Layer
- [ ] Create `test/security/ConstellationSecurityManagerTest.kt`:
  - [ ] Test registration flow
  - [ ] Test unlock flow
  - [ ] Test lockout behavior
  - [ ] Test attempt tracking persistence
  - [ ] Test panic wipe
  - [ ] Test recovery flow

### 6.3 Integration Tests
- [ ] Create `androidTest/ConstellationFlowTest.kt`:
  - [ ] Test full setup → confirm → unlock flow
  - [ ] Test failed unlock → lockout flow
  - [ ] Test recovery phrase flow
  - [ ] Test app kill → restart (state persistence)
  - [ ] Test rotation during setup/unlock

### 6.4 Manual Testing Checklist
- [ ] Test on physical device (minimum API 26)
- [ ] Test constellation generation speed (< 500ms)
- [ ] Test with dirty/wet fingers
- [ ] Test in bright sunlight (visibility)
- [ ] Test with screen protector
- [ ] Test lockout persists after app kill
- [ ] Test across different screen sizes:
  - [ ] Phone 16:9
  - [ ] Phone 18:9
  - [ ] Phone 20:9
  - [ ] Tablet 4:3
  - [ ] Foldable (if available)
- [ ] Test 100 consecutive unlock attempts (no crashes)
- [ ] Test memory stability (LeakCanary) over 50 lock/unlock cycles

---

## Phase 7: Documentation

- [ ] Update `ARCHITECTURE.md` with Constellation Block description
- [ ] Add security documentation:
  - [ ] Entropy calculation explanation (~72 bits)
  - [ ] Threat model
  - [ ] Keystore usage
  - [ ] Grid quantization algorithm
- [ ] Create user-facing recovery documentation
- [ ] Add inline KDoc comments to all public APIs
- [ ] Create architecture diagram showing constellation auth flow

---

## Phase 8: Migration & Compatibility

### 8.1 Create Migration Manager
- [ ] Create `migration/AuthMigrationManager.kt`:
  ```kotlin
  enum class MigrationState {
      RHYTHM_ONLY,
      DUAL_AUTH,        // Both rhythm and constellation available
      CONSTELLATION_ONLY
  }

  class AuthMigrationManager(
      private val rhythmManager: RhythmSecurityManager,
      private val constellationManager: ConstellationSecurityManager,
      private val storage: SecureStorage
  ) {
      companion object {
          private const val KEY_MIGRATION_STATE = "auth.migration.state"
          private const val DUAL_AUTH_PERIOD_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
      }

      suspend fun checkMigrationNeeded(): MigrationState {
          val hasRhythm = rhythmManager.hasRealRhythm()
          val hasConstellation = constellationManager.hasRealConstellation()

          return when {
              hasRhythm && !hasConstellation -> MigrationState.RHYTHM_ONLY
              hasRhythm && hasConstellation -> MigrationState.DUAL_AUTH
              !hasRhythm && hasConstellation -> MigrationState.CONSTELLATION_ONLY
              else -> MigrationState.RHYTHM_ONLY // Default to rhythm
          }
      }

      suspend fun shouldPromptConstellationSetup(): Boolean {
          return checkMigrationNeeded() == MigrationState.RHYTHM_ONLY
      }
  }
  ```

### 8.2 Update Auth Flow Logic
- [ ] Update `AppStateManager` to handle migration states
- [ ] Show constellation setup prompt after successful rhythm unlock
- [ ] Implement "Try Constellation Lock" banner in settings
- [ ] Implement "Disable Rhythm Lock" option after 30 days of dual auth

---

## Phase 9: Polish & Production Readiness

### 9.1 Internationalization
- [ ] Create `res/values/strings_constellation.xml`:
  ```xml
  <resources>
      <string name="constellation_setup_title">Create Your Constellation Pattern</string>
      <string name="constellation_setup_instruction">Tap %1$d-%2$d stars in sequence</string>
      <string name="constellation_confirm_title">Confirm Your Pattern</string>
      <string name="constellation_unlock_title">Unlock</string>
      <string name="constellation_error_incorrect">Pattern incorrect</string>
      <string name="constellation_error_attempts_remaining">%d attempts remaining</string>
      <string name="constellation_lockout_message">Too many attempts. Try again in %d minutes.</string>
      <string name="constellation_reset_button">Reset</string>
      <string name="constellation_forgot_pattern">Forgot pattern?</string>
      <string name="constellation_quality_weak">Weak pattern - spread stars more</string>
      <string name="constellation_quality_good">Good pattern</string>
      <string name="constellation_quality_strong">Strong pattern</string>
      <string name="constellation_points_too_close">Stars too close together</string>
  </resources>
  ```

- [ ] Replace all hardcoded strings with `stringResource()`

### 9.2 Accessibility
- [ ] Add content descriptions to all interactive elements
- [ ] Test with TalkBack enabled
- [ ] Test with large display size
- [ ] Consider adding audio feedback option
- [ ] Add haptic feedback strength setting

### 9.3 Performance Optimization
- [ ] Profile constellation generation (target < 500ms on API 26)
- [ ] Consider caching constellation bitmap (encrypted)
- [ ] Optimize Canvas drawing (use hardware acceleration)
- [ ] Add loading shimmer during generation

### 9.4 Security Hardening
- [ ] Verify FLAG_SECURE on all constellation screens
- [ ] Add velocity check (reject bot-like tapping)
- [ ] Add screenshot detection logging
- [ ] Security audit by second developer

---

## Verification Criteria (Definition of Done)

### Core Functionality
- [ ] Constellation pattern can be set up (6-8 stars)
- [ ] Pattern can be confirmed (must match first attempt on grid)
- [ ] Pattern can unlock app successfully
- [ ] Failed attempts trigger lockout (5 attempts → 5 min)
- [ ] Lockout persists after app kill/restart
- [ ] Recovery phrase flow works end-to-end

### Security
- [ ] Pattern encrypted with Android Keystore (hardware-backed)
- [ ] Lockout state persisted in SecureStorage
- [ ] FLAG_SECURE prevents screenshots
- [ ] Constant-time pattern matching (no timing attacks)
- [ ] Panic wipe after 20 total failed attempts

### Quality
- [ ] All Phase 6.1 unit tests passing (>90% coverage)
- [ ] All Phase 6.3 integration tests passing
- [ ] Manual testing completed on 2+ physical devices
- [ ] No crashes in 100 consecutive unlock attempts
- [ ] Memory stable (LeakCanary) over 50 lock/unlock cycles
- [ ] No lint errors or warnings

### Architecture
- [ ] Follows Block pattern (zero cross-block dependencies)
- [ ] Uses MVI pattern for all ViewModels
- [ ] All I/O operations use suspend functions
- [ ] Uses Koin for dependency injection
- [ ] Events published via EventBus

### Polish
- [ ] All strings internationalized
- [ ] Accessible with TalkBack
- [ ] Constellation generates in < 500ms
- [ ] Follows VOID design system
- [ ] Documentation complete

---

## Implementation Order Recommendation

### Week 1: Foundation (Phase 2-3)
- Day 1-2: Domain layer (Models, StarGenerator, StarQuantizer, ConstellationMatcher)
- Day 3-4: Security layer (ConstellationSecurityManager)
- Day 5: Unit tests for domain + security

### Week 2: UI Layer (Phase 4)
- Day 1-2: Setup screen (State, ViewModel, Composable)
- Day 3: Confirm screen
- Day 4: Unlock screen
- Day 5: UI components (ConstellationView, PatternQualityIndicator)

### Week 3: Integration (Phase 5-6)
- Day 1-2: Block manifest, routes, Koin module
- Day 3-4: Integration tests
- Day 5: Manual testing on devices

### Week 4: Polish & Migration (Phase 7-9)
- Day 1-2: Migration manager, auth flow updates
- Day 3: Internationalization, accessibility
- Day 4: Performance optimization
- Day 5: Final security audit, documentation

---

## Notes

- **Reference Implementation**: `/Users/magz/Documents/Coding/void-app/blocks/rhythm/` - Follow this pattern exactly
- **Security Pattern**: Match `RhythmSecurityManager` for Keystore usage, lockout logic, recovery flow
- **State Management**: All ViewModels must extend `MviViewModel<State, Intent, Effect>`
- **Navigation**: Use `Navigator` interface, not NavController directly
- **Storage**: All persistence via `SecureStorage` interface (SQLCipher-backed)
- **Crypto**: Use `CryptoProvider` for hashing, derivation, encryption
- **Events**: Publish constellation events via `EventBus` for cross-block communication

---

## Risk Mitigation

1. **Deterministic Generation Variance**: Test extensively across different Android versions and devices
2. **Grid Quantization Edge Cases**: Add boundary tests for 0.0, 0.999, exact grid lines
3. **Memory Leaks**: Use LeakCanary throughout development
4. **Keystore Invalidation**: Handle lock screen security changes gracefully
5. **Migration Complexity**: Keep dual auth period (30 days) to ensure smooth transition

---

## Success Metrics

- [ ] 0 crashes in production (first 30 days)
- [ ] < 1% lockout rate (users locked out accidentally)
- [ ] < 0.1% recovery phrase usage (pattern forgotten)
- [ ] > 95% migration completion rate (rhythm → constellation)
- [ ] < 500ms average constellation generation time
- [ ] No security vulnerabilities identified in audit
