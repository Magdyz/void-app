package com.void.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.void.block.identity.ui.IdentityScreen
import com.void.block.rhythm.ui.*
import com.void.slate.navigation.Routes
import org.koin.androidx.compose.koinViewModel

/**
 * Main navigation graph for VOID app.
 *
 * Navigation Flow:
 * 1. First Launch: Identity → Rhythm Setup → Rhythm Confirm → Recovery Phrase → Messages
 * 2. Subsequent Launch: Rhythm Unlock → Messages
 * 3. Recovery: Recovery Input → Rhythm Setup → Messages
 */
@Composable
fun VoidNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // ═══════════════════════════════════════════════════════════════
        // Identity Block Routes
        // ═══════════════════════════════════════════════════════════════

        composable(Routes.IDENTITY_GENERATE) {
            IdentityScreen(
                onComplete = {
                    // Identity created → Set up rhythm authentication
                    navController.navigate(Routes.RHYTHM_SETUP) {
                        popUpTo(Routes.IDENTITY_GENERATE) { inclusive = true }
                    }
                },
                onSkip = null // Can't skip identity generation
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Rhythm Block Routes
        // ═══════════════════════════════════════════════════════════════

        composable(Routes.RHYTHM_SETUP) {
            val setupViewModel: RhythmSetupViewModel = koinViewModel()

            RhythmSetupScreen(
                onComplete = { pattern ->
                    setupViewModel.onPatternCreated(pattern)
                    // Navigate to confirmation
                    navController.navigate(Routes.RHYTHM_CONFIRM)
                },
                onCancel = {
                    // Can't cancel during onboarding
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.RHYTHM_CONFIRM) {
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
                        navController.popBackStack()
                    }
                )

                // Handle registration success
                LaunchedEffect(confirmState) {
                    when (val state = confirmState) {
                        is RhythmConfirmState.Success -> {
                            // Navigate to recovery phrase display
                            navController.navigate(Routes.RHYTHM_RECOVERY) {
                                // Clear back stack - can't go back from recovery phrase
                                popUpTo(Routes.RHYTHM_SETUP) { inclusive = true }
                            }
                        }
                        is RhythmConfirmState.Error -> {
                            // Show error, stay on screen
                        }
                        else -> {}
                    }
                }
            } else {
                // Pattern is null - shouldn't happen, go back
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        composable(Routes.RHYTHM_RECOVERY) {
            val confirmViewModel: RhythmConfirmViewModel = koinViewModel()
            val confirmState by confirmViewModel.state.collectAsState()

            when (val state = confirmState) {
                is RhythmConfirmState.Success -> {
                    // Display recovery phrase after successful registration
                    RecoveryPhraseScreen(
                        recoveryPhrase = state.recoveryPhrase,
                        onConfirmed = {
                            // Setup complete! Navigate to main app
                            navController.navigate(Routes.MESSAGES_LIST) {
                                // Clear all onboarding screens from back stack
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onBack = {
                            // Can't go back from recovery phrase during onboarding
                        }
                    )
                }
                else -> {
                    // TODO: Implement recovery phrase input screen for account recovery
                    // For now, just navigate to unlock screen
                    LaunchedEffect(Unit) {
                        navController.navigate(Routes.RHYTHM_UNLOCK) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }
        }

        composable(Routes.RHYTHM_UNLOCK) {
            val unlockViewModel: RhythmUnlockViewModel = koinViewModel()
            val unlockResult by unlockViewModel.unlockResult.collectAsState()
            val unlockSuccess by unlockViewModel.unlockSuccess.collectAsState()

            RhythmUnlockScreen(
                onUnlock = { pattern ->
                    unlockViewModel.attemptUnlock(pattern)
                },
                unlockState = unlockResult,
                onForgot = {
                    // Navigate to recovery phrase input
                    navController.navigate(Routes.RHYTHM_RECOVERY)
                }
            )

            // Handle successful unlock
            LaunchedEffect(unlockSuccess) {
                unlockSuccess?.let { mode ->
                    // Navigate to main app
                    navController.navigate(Routes.MESSAGES_LIST) {
                        popUpTo(Routes.RHYTHM_UNLOCK) { inclusive = true }
                    }
                    unlockViewModel.clearUnlockSuccess()
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // Main App Routes (Placeholder)
        // ═══════════════════════════════════════════════════════════════

        composable(Routes.MESSAGES_LIST) {
            // TODO: Implement messages list screen
            // For now, show placeholder
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text(
                    text = "🎉 Welcome to VOID!\n\nMessages List\n(Coming Soon)",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
