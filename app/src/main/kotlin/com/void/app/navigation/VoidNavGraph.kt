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
                    println("VOID_DEBUG: onComplete callback called, navigating to ${Routes.RHYTHM_SETUP}")
                    try {
                        navController.navigate(Routes.RHYTHM_SETUP)
                        println("VOID_DEBUG: Navigation call completed successfully")
                    } catch (e: Exception) {
                        println("VOID_DEBUG: Navigation failed with error: ${e.message}")
                        e.printStackTrace()
                    }
                    // Do NOT popUpTo here. The back stack will be cleared later at Routes.MESSAGES_LIST.
                },
                onSkip = null // Can't skip identity generation
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Rhythm Block Routes
        // ═══════════════════════════════════════════════════════════════

        composable(Routes.RHYTHM_SETUP) {
            println("VOID_DEBUG: RHYTHM_SETUP composable entered")
            val setupViewModel: RhythmSetupViewModel = koinViewModel()
            println("VOID_DEBUG: RhythmSetupViewModel obtained")

            RhythmSetupScreen(
                onComplete = { pattern ->
                    println("VOID_DEBUG: RhythmSetupScreen onComplete called with pattern: ${pattern.intervals}")
                    setupViewModel.onPatternCreated(pattern)
                    // Navigate to confirmation
                    navController.navigate(Routes.RHYTHM_CONFIRM)
                },
                onCancel = {
                    println("VOID_DEBUG: RhythmSetupScreen onCancel called")
                    // Can't cancel during onboarding
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.RHYTHM_CONFIRM) {
            println("VOID_DEBUG: RHYTHM_CONFIRM composable entered")

            // CRITICAL: Get the RhythmSetupViewModel from the RHYTHM_SETUP backstack entry
            // This ensures we use the SAME ViewModel instance that has the pattern
            val setupBackStackEntry = try {
                navController.getBackStackEntry(Routes.RHYTHM_SETUP)
            } catch (e: IllegalArgumentException) {
                // Backstack entry doesn't exist - we've navigated away
                println("VOID_DEBUG: RHYTHM_SETUP not in backstack, returning early")
                return@composable
            }
            val setupViewModel: RhythmSetupViewModel = koinViewModel(viewModelStoreOwner = setupBackStackEntry)
            val confirmViewModel: RhythmConfirmViewModel = koinViewModel()
            val setupState by setupViewModel.state.collectAsState()
            val confirmState by confirmViewModel.state.collectAsState()

            println("VOID_DEBUG: Setup state: $setupState")
            println("VOID_DEBUG: Confirm state: $confirmState")

            // Get pattern from setup state
            val pattern = (setupState as? RhythmSetupState.PatternCreated)?.pattern
            println("VOID_DEBUG: Pattern from setup state: ${pattern?.intervals}")

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
                            // Don't pop backstack here - we'll clear it when reaching Messages
                            navController.navigate(Routes.RHYTHM_RECOVERY)
                        }
                        is RhythmConfirmState.Error -> {
                            // Show error, stay on screen
                        }
                        else -> {}
                    }
                }
            } else {
                // Pattern is null - shouldn't happen, go back
                println("VOID_DEBUG: Pattern is null, going back to previous screen")
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        composable(Routes.RHYTHM_RECOVERY) {
            // Safely get the RhythmConfirmViewModel from the RHYTHM_CONFIRM backstack entry
            // This might not exist if we've navigated away and backstack was cleared
            val confirmBackStackEntry = try {
                navController.getBackStackEntry(Routes.RHYTHM_CONFIRM)
            } catch (e: IllegalArgumentException) {
                // Backstack entry doesn't exist - we've navigated away
                // Return early to avoid crash
                return@composable
            }
            val confirmViewModel: RhythmConfirmViewModel = koinViewModel(viewModelStoreOwner = confirmBackStackEntry)
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
