package com.void.block.rhythm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.void.block.rhythm.domain.RhythmCapture
import com.void.block.rhythm.domain.RhythmMatcher
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.security.RhythmSecurityManager
import com.void.block.rhythm.ui.*
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
 * Rhythm Block
 *
 * Provides the rhythm key authentication system.
 * Handles capture, verification, and recovery.
 */
@Block(id = "rhythm", enabledByDefault = true)
class RhythmBlock : BlockManifest {

    override val id: String = "rhythm"

    override val routes: List<Route> = listOf(
        Route.Screen(Routes.RHYTHM_SETUP, "Set Up Rhythm Key"),
        Route.Screen(Routes.RHYTHM_CONFIRM, "Confirm Rhythm"),
        Route.Screen(Routes.RHYTHM_UNLOCK, "Unlock"),
        Route.Screen(Routes.RHYTHM_RECOVERY, "Recovery"),
    )

    override fun Module.install() {
        // Domain layer
        single { RhythmMatcher() }
        factory { RhythmCapture() }

        // Security layer
        single {
            RhythmSecurityManager(
                keystoreManager = get(),
                matcher = get(),
                storage = get(),
                crypto = get()
            )
        }

        // UI layer (ViewModels)
        viewModel { RhythmSetupViewModel() }
        viewModel { RhythmConfirmViewModel(get()) }
        viewModel { RhythmUnlockViewModel(get()) }
        viewModel { RecoveryViewModel(get()) }
    }

    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        // Rhythm Setup - Create initial pattern
        composable(Routes.RHYTHM_SETUP) {
            val viewModel: RhythmSetupViewModel = koinViewModel()

            RhythmSetupScreen(
                onComplete = { pattern ->
                    viewModel.onPatternCreated(pattern)
                    // Navigate to confirmation with pattern
                    navigator.navigate(Routes.RHYTHM_CONFIRM)
                },
                onCancel = {
                    navigator.goBack()
                }
            )
        }

        // Rhythm Confirm - Verify pattern and register
        composable(Routes.RHYTHM_CONFIRM) { backStackEntry ->
            val setupViewModel: RhythmSetupViewModel = koinViewModel()
            val confirmViewModel: RhythmConfirmViewModel = koinViewModel()
            val setupState by setupViewModel.state.collectAsState()
            val confirmState by confirmViewModel.state.collectAsState()

            // Get pattern from setup state
            val pattern = (setupState as? RhythmSetupState.PatternCreated)?.pattern

            if (pattern != null) {
                RhythmConfirmScreen(
                    originalPattern = pattern,
                    onConfirmed = { confirmedPattern ->
                        confirmViewModel.onPatternConfirmed(confirmedPattern)
                    },
                    onRetry = {
                        // Go back to setup to create new pattern
                        navigator.goBack()
                    }
                )

                // Handle registration success
                LaunchedEffect(confirmState) {
                    if (confirmState is RhythmConfirmState.Success) {
                        val successState = confirmState as RhythmConfirmState.Success
                        // Navigate to recovery phrase display
                        navigator.navigate(Routes.RHYTHM_RECOVERY)
                    }
                }
            }
        }

        // Recovery Phrase Display/Input
        composable(Routes.RHYTHM_RECOVERY) {
            val confirmViewModel: RhythmConfirmViewModel = koinViewModel()
            val confirmState by confirmViewModel.state.collectAsState()

            when (val state = confirmState) {
                is RhythmConfirmState.Success -> {
                    // Display recovery phrase after successful registration
                    RecoveryPhraseScreen(
                        recoveryPhrase = state.recoveryPhrase,
                        onConfirmed = {
                            // Setup complete, navigate to main app
                            navigator.navigateAndClear(Routes.MESSAGES_LIST)
                        },
                        onBack = {
                            navigator.goBack()
                        }
                    )
                }
                else -> {
                    // Recovery phrase input screen (for account recovery)
                    // TODO: Implement recovery phrase input screen
                }
            }
        }

        // Rhythm Unlock - Main unlock screen
        composable(Routes.RHYTHM_UNLOCK) {
            val viewModel: RhythmUnlockViewModel = koinViewModel()
            val unlockResult by viewModel.unlockResult.collectAsState()
            val unlockSuccess by viewModel.unlockSuccess.collectAsState()

            RhythmUnlockScreen(
                onUnlock = { pattern ->
                    viewModel.attemptUnlock(pattern)
                },
                unlockState = unlockResult,
                onForgot = {
                    // Navigate to recovery phrase input
                    navigator.navigate(Routes.RHYTHM_RECOVERY)
                }
            )

            // Handle successful unlock
            LaunchedEffect(unlockSuccess) {
                unlockSuccess?.let { mode ->
                    // Navigate to main app
                    navigator.navigateAndClear(Routes.MESSAGES_LIST)
                    viewModel.clearUnlockSuccess()
                }
            }
        }
    }
}

val rhythmModule = module {
    // Domain layer
    single { RhythmMatcher() }
    factory { RhythmCapture() }

    // Security layer
    single {
        RhythmSecurityManager(
            keystoreManager = get(),
            matcher = get(),
            storage = get(),
            crypto = get()
        )
    }

    // UI layer
    viewModel { RhythmSetupViewModel() }
    viewModel { RhythmConfirmViewModel(get()) }
    viewModel { RhythmUnlockViewModel(get()) }
    viewModel { RecoveryViewModel(get()) }
}
