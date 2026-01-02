package app.voidapp.block.constellation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.voidapp.block.constellation.domain.ConstellationMatcher
import app.voidapp.block.constellation.domain.StarGenerator
import app.voidapp.block.constellation.domain.StarQuantizer
import app.voidapp.block.constellation.security.ConstellationSecurityManager
import app.voidapp.block.constellation.ui.confirm.ConstellationConfirmScreen
import app.voidapp.block.constellation.ui.confirm.ConstellationConfirmState
import app.voidapp.block.constellation.ui.confirm.ConstellationConfirmViewModel
import app.voidapp.block.constellation.ui.setup.ConstellationSetupScreen
import app.voidapp.block.constellation.ui.setup.ConstellationSetupState
import app.voidapp.block.constellation.ui.setup.ConstellationSetupViewModel
import app.voidapp.block.constellation.ui.unlock.ConstellationUnlockScreen
import app.voidapp.block.constellation.ui.unlock.ConstellationUnlockViewModel
import com.void.slate.block.Block
import com.void.slate.block.BlockManifest
import com.void.slate.navigation.Navigator
import com.void.slate.navigation.Route
import com.void.slate.navigation.Routes
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Constellation Block
 *
 * Provides tap-sequence authentication on deterministic visual patterns.
 * Security: 72+ bits entropy, hardware-backed encryption, grid quantization.
 */
@Block(id = "constellation", enabledByDefault = true)
class ConstellationBlock : BlockManifest {

    override val id: String = "constellation"

    override val routes: List<Route> = listOf(
        Route.Screen(Routes.CONSTELLATION_SETUP, "Set Up Constellation"),
        Route.Screen(Routes.CONSTELLATION_CONFIRM, "Confirm Pattern"),
        Route.Screen(Routes.CONSTELLATION_UNLOCK, "Unlock"),
        Route.Screen(Routes.CONSTELLATION_RECOVERY, "Recovery")
    )

    override fun Module.install() {
        // Domain layer
        single { StarQuantizer() }
        single { ConstellationMatcher(get()) }
        single { StarGenerator(get()) }

        // Security layer
        single {
            ConstellationSecurityManager(
                keystoreManager = get(),
                storage = get(),
                crypto = get(),
                matcher = get(),
                getIdentitySeed = get()  // Provided by app module
            )
        }

        // UI layer (ViewModels)
        viewModel {
            ConstellationSetupViewModel(
                starGenerator = get(),
                matcher = get(),
                quantizer = get(),
                getIdentitySeed = get()  // Provided by app module
            )
        }
        viewModel {
            ConstellationConfirmViewModel(
                securityManager = get(),
                matcher = get(),  // V2: Add matcher
                starGenerator = get(),
                quantizer = get(),
                getIdentitySeed = get()  // Provided by app module
            )
        }
        viewModel {
            ConstellationUnlockViewModel(
                securityManager = get(),
                matcher = get(),  // V2: Add matcher
                starGenerator = get(),
                quantizer = get(),
                getIdentitySeed = get()  // Provided by app module
            )
        }
    }

    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        // Constellation Setup - Create initial pattern
        composable(Routes.CONSTELLATION_SETUP) {
            val viewModel: ConstellationSetupViewModel = koinViewModel()

            ConstellationSetupScreen(
                onComplete = { pattern, landmarks, metadata, constellation, screenWidth, screenHeight ->
                    // V2: Store verification hash via metadata
                    // Navigation to confirmation happens automatically
                    navigator.navigate(Routes.CONSTELLATION_CONFIRM)
                },
                onCancel = {
                    navigator.goBack()
                },
                viewModel = viewModel
            )
        }

        // Constellation Confirm - Verify pattern and register
        composable(Routes.CONSTELLATION_CONFIRM) {
            val setupViewModel: ConstellationSetupViewModel = koinViewModel()
            val confirmViewModel: ConstellationConfirmViewModel = koinViewModel()
            val setupState by setupViewModel.state.collectAsState()
            val confirmState by confirmViewModel.state.collectAsState()

            // Get pattern, landmarks, and constellation from setup state
            val patternCreated = setupState as? ConstellationSetupState.PatternCreated
            val pattern = patternCreated?.pattern
            val landmarks = patternCreated?.landmarks ?: emptyList()
            val metadata = patternCreated?.metadata
            val constellation = patternCreated?.constellation
            val screenWidth = patternCreated?.screenWidth ?: 0
            val screenHeight = patternCreated?.screenHeight ?: 0

            if (pattern != null && constellation != null && landmarks.isNotEmpty()) {
                ConstellationConfirmScreen(
                    firstPattern = pattern,
                    landmarks = landmarks,  // V2: Pass landmarks
                    metadata = metadata,    // V2: Pass metadata
                    constellation = constellation,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    onSuccess = {
                        // Navigate to main app or recovery phrase
                        navigator.navigateAndClear(Routes.MESSAGES_LIST)
                    },
                    onCancel = {
                        // Go back to setup to create new pattern
                        navigator.goBackTo(Routes.CONSTELLATION_SETUP, inclusive = true)
                        navigator.navigate(Routes.CONSTELLATION_SETUP)
                    },
                    viewModel = confirmViewModel
                )
            }
        }

        // Constellation Unlock - Main unlock screen
        composable(Routes.CONSTELLATION_UNLOCK) {
            val viewModel: ConstellationUnlockViewModel = koinViewModel()

            ConstellationUnlockScreen(
                onUnlockSuccess = {
                    // Navigate to main app
                    navigator.navigateAndClear(Routes.MESSAGES_LIST)
                },
                onForgotPattern = {
                    // Navigate to recovery phrase input
                    navigator.navigate(Routes.CONSTELLATION_RECOVERY)
                },
                viewModel = viewModel
            )
        }

        // Recovery - Recovery phrase input/display
        composable(Routes.CONSTELLATION_RECOVERY) {
            // TODO: Implement recovery phrase screen
            // For now, placeholder
        }
    }
}

val constellationModule = module {
    // Domain layer
    single { StarQuantizer() }
    single { ConstellationMatcher(get()) }
    single { StarGenerator(get()) }

    // Security layer
    single {
        ConstellationSecurityManager(
            keystoreManager = get(),
            storage = get(),
            crypto = get(),
            matcher = get(),
            getIdentitySeed = get()  // Provided by app module
        )
    }

    // UI layer
    viewModel {
        ConstellationSetupViewModel(
            starGenerator = get(),
            matcher = get(),
            quantizer = get(),
            getIdentitySeed = get()  // Provided by app module
        )
    }
    viewModel {
        ConstellationConfirmViewModel(
            securityManager = get(),
            matcher = get(),  // V2: Add matcher
            starGenerator = get(),
            quantizer = get(),
            getIdentitySeed = get()  // Provided by app module
        )
    }
    viewModel {
        ConstellationUnlockViewModel(
            securityManager = get(),
            matcher = get(),  // V2: Add matcher
            starGenerator = get(),
            quantizer = get(),
            getIdentitySeed = get()  // Provided by app module
        )
    }
}
