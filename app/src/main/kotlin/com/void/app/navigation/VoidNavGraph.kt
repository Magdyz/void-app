package com.void.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.voidapp.block.constellation.ui.auth.AuthMethod
import app.voidapp.block.constellation.ui.auth.AuthMethodSelectionScreen
import app.voidapp.block.constellation.ui.auth.AuthMethodSelectionViewModel
import app.voidapp.block.constellation.ui.biometric.BiometricSetupScreen
import app.voidapp.block.constellation.ui.biometric.BiometricSetupViewModel
import app.voidapp.block.constellation.ui.setup.ConstellationSetupScreen
import app.voidapp.block.constellation.ui.setup.ConstellationSetupViewModel
import app.voidapp.block.constellation.ui.setup.ConstellationSetupState
import app.voidapp.block.constellation.ui.confirm.ConstellationConfirmScreen
import app.voidapp.block.constellation.ui.confirm.ConstellationConfirmViewModel
import app.voidapp.block.constellation.ui.confirm.ConstellationConfirmState
import app.voidapp.block.constellation.ui.unlock.ConstellationUnlockScreen
import app.voidapp.block.constellation.ui.unlock.ConstellationUnlockViewModel
import com.void.block.identity.data.IdentityRepository
import com.void.block.identity.ui.IdentityScreen
import com.void.slate.navigation.Routes
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Main navigation graph for VOID app.
 *
 * Navigation Flow:
 * 1. First Launch: Identity → Constellation Setup → Constellation Confirm → Messages
 * 2. Subsequent Launch: Constellation Unlock → Messages
 * 3. Recovery: Recovery Input → Constellation Setup → Messages
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
                    // Identity created → Choose authentication method
                    println("VOID_DEBUG: onComplete callback called, navigating to ${Routes.CONSTELLATION_AUTH_METHOD}")
                    try {
                        navController.navigate(Routes.CONSTELLATION_AUTH_METHOD)
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
        // Constellation Block Routes
        // ═══════════════════════════════════════════════════════════════

        composable(Routes.CONSTELLATION_AUTH_METHOD) {
            val viewModel: AuthMethodSelectionViewModel = koinViewModel()

            AuthMethodSelectionScreen(
                onMethodSelected = { method ->
                    println("VOID_DEBUG: Auth method selected: $method")
                    when (method) {
                        AuthMethod.CONSTELLATION -> {
                            // Go directly to constellation setup
                            println("VOID_DEBUG: Navigating to ${Routes.CONSTELLATION_SETUP}")
                            navController.navigate(Routes.CONSTELLATION_SETUP)
                        }
                        AuthMethod.BIOMETRIC -> {
                            // Go to biometric setup first, then constellation backup
                            println("VOID_DEBUG: Navigating to ${Routes.CONSTELLATION_BIOMETRIC_SETUP}")
                            navController.navigate(Routes.CONSTELLATION_BIOMETRIC_SETUP)
                        }
                    }
                },
                onCancel = {
                    // Can't cancel during onboarding
                    navController.popBackStack()
                },
                viewModel = viewModel
            )
        }

        composable(Routes.CONSTELLATION_BIOMETRIC_SETUP) {
            println("VOID_DEBUG: CONSTELLATION_BIOMETRIC_SETUP composable entered")
            BiometricSetupScreen(
                onBiometricEnrolled = {
                    println("VOID_DEBUG: Biometric enrolled, navigating to ${Routes.CONSTELLATION_SETUP}")
                    // After biometric setup, go to constellation for backup pattern
                    navController.navigate(Routes.CONSTELLATION_SETUP)
                },
                onCancel = {
                    // Go back to auth method selection
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.CONSTELLATION_SETUP) {
            println("VOID_DEBUG: CONSTELLATION_SETUP composable entered")
            val setupViewModel: ConstellationSetupViewModel = koinViewModel()
            println("VOID_DEBUG: ConstellationSetupViewModel obtained")

            ConstellationSetupScreen(
                onComplete = { pattern, verificationHash, algorithmVersion, constellation, screenWidth, screenHeight ->
                    println("VOID_DEBUG: ConstellationSetupScreen onComplete called")
                    // Navigate to confirmation (ViewModel retains the state including bitmap)
                    navController.navigate(Routes.CONSTELLATION_CONFIRM)
                },
                onCancel = {
                    println("VOID_DEBUG: ConstellationSetupScreen onCancel called")
                    // Can't cancel during onboarding
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.CONSTELLATION_CONFIRM) {
            println("VOID_DEBUG: CONSTELLATION_CONFIRM composable entered")

            // Get the ConstellationSetupViewModel from the CONSTELLATION_SETUP backstack entry
            // This ensures we use the SAME ViewModel instance that has the pattern
            val setupBackStackEntry = try {
                navController.getBackStackEntry(Routes.CONSTELLATION_SETUP)
            } catch (e: IllegalArgumentException) {
                // Backstack entry doesn't exist - we've navigated away
                println("VOID_DEBUG: CONSTELLATION_SETUP not in backstack, returning early")
                return@composable
            }
            val setupViewModel: ConstellationSetupViewModel = koinViewModel(viewModelStoreOwner = setupBackStackEntry)
            val setupState by setupViewModel.state.collectAsState()

            println("VOID_DEBUG: Setup state: $setupState")

            // Get pattern, landmarks, and constellation from setup state
            val patternState = setupState as? ConstellationSetupState.PatternCreated
            val pattern = patternState?.pattern
            val landmarks = patternState?.landmarks ?: emptyList()  // V2: Extract landmarks
            val metadata = patternState?.metadata  // V2: Extract metadata
            val constellation = patternState?.constellation
            val screenWidth = patternState?.screenWidth ?: 0
            val screenHeight = patternState?.screenHeight ?: 0

            println("VOID_DEBUG: Pattern from setup state: $pattern")
            println("VOID_DEBUG: Constellation dimensions: ${screenWidth}x${screenHeight}")

            if (pattern != null && constellation != null && landmarks.isNotEmpty()) {
                ConstellationConfirmScreen(
                    firstPattern = pattern,
                    landmarks = landmarks,          // V2: Pass landmarks
                    metadata = metadata,            // V2: Pass metadata
                    constellation = constellation,  // Pass exact same bitmap
                    screenWidth = screenWidth,      // Lock dimensions
                    screenHeight = screenHeight,
                    onSuccess = {
                        println("VOID_DEBUG: Constellation pattern confirmed successfully")
                        // Navigate directly to main app (constellation setup complete)
                        navController.navigate(Routes.MESSAGES_LIST) {
                            // Clear all onboarding screens from back stack
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onCancel = {
                        // Go back to setup to create new pattern
                        navController.popBackStack()
                    }
                )
            } else {
                // Pattern is null - shouldn't happen, go back
                println("VOID_DEBUG: Pattern is null, going back to previous screen")
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        composable(Routes.CONSTELLATION_RECOVERY) {
            // TODO: Implement recovery phrase input screen for account recovery
            // For now, show a placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Recovery Phrase Screen - Coming Soon",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
            }
        }

        composable(Routes.CONSTELLATION_UNLOCK) {
            ConstellationUnlockScreen(
                onUnlockSuccess = {
                    println("VOID_DEBUG: Constellation unlock successful")
                    // Navigate to main app
                    navController.navigate(Routes.MESSAGES_LIST) {
                        popUpTo(Routes.CONSTELLATION_UNLOCK) { inclusive = true }
                    }
                },
                onForgotPattern = {
                    // Navigate to recovery phrase input
                    navController.navigate(Routes.CONSTELLATION_RECOVERY)
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Messaging Block Routes
        // ═══════════════════════════════════════════════════════════════

        composable(Routes.MESSAGES_LIST) {
            val identityRepository: IdentityRepository = koinInject()

            // Retrieve user's identity
            val userIdentity by produceState<String?>(initialValue = null) {
                value = identityRepository.getIdentity()?.formatted
            }

            // QR code with auto-regeneration every 5 minutes
            var qrCodeJson by remember { mutableStateOf<String?>(null) }
            var qrGenerationCount by remember { mutableStateOf(0) }

            // Auto-regenerate QR code every 5 minutes
            LaunchedEffect(qrGenerationCount) {
                try {
                    val identity = identityRepository.getIdentity()
                    val publicKey = identityRepository.getPublicEncryptionKey()
                    val identityKey = identityRepository.getPublicIdentityKey()
                    val mailboxSeed = identityRepository.getMailboxSeed()

                    android.util.Log.d("VOID_QR", "🔍 [QR_GENERATE] Generating QR code data (generation #$qrGenerationCount):")
                    android.util.Log.d("VOID_QR", "  Identity: ${identity?.formatted}")
                    android.util.Log.d("VOID_QR", "  PublicKey: ${publicKey?.size} bytes")
                    android.util.Log.d("VOID_QR", "  IdentityKey: ${identityKey?.size} bytes")
                    android.util.Log.d("VOID_QR", "  MailboxSeed: ${mailboxSeed?.size} bytes (first 16: ${mailboxSeed?.take(16)?.joinToString("") { "%02x".format(it) }})")

                    if (identity != null && publicKey != null && identityKey != null && mailboxSeed != null) {
                        val threeWordIdentity = com.void.block.contacts.domain.ThreeWordIdentity(
                            word1 = identity.words[0],
                            word2 = identity.words[1],
                            word3 = identity.words[2]
                        )
                        val qrData = com.void.block.contacts.domain.ContactQRData(
                            identity = threeWordIdentity,
                            publicKey = android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP),
                            identityKey = android.util.Base64.encodeToString(identityKey, android.util.Base64.NO_WRAP),
                            mailboxSeed = android.util.Base64.encodeToString(mailboxSeed, android.util.Base64.NO_WRAP),
                            timestamp = System.currentTimeMillis()
                        )
                        android.util.Log.d("VOID_QR", "✅ [QR_GENERATE] QR data created, JSON length: ${qrData.toJson().length}")
                        qrCodeJson = qrData.toJson()

                        // Auto-regenerate after 5 minutes
                        kotlinx.coroutines.delay(5 * 60 * 1000L) // 5 minutes
                        qrGenerationCount++
                        android.util.Log.d("VOID_QR", "⏰ [QR_REGENERATE] Triggering regeneration...")
                    } else {
                        android.util.Log.e("VOID_QR", "❌ [QR_GENERATE] Missing required data!")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VOID_QR", "❌ [QR_GENERATE] Failed to generate QR code JSON", e)
                    qrCodeJson = null
                }
            }

            com.void.block.messaging.ui.ConversationListScreen(
                onConversationClick = { conversationId ->
                    // Use conversationId as contactId for 1:1 chats
                    navController.navigate("messages/chat/$conversationId")
                },
                onNewConversation = {
                    navController.navigate(Routes.CONTACTS_LIST)
                },
                userIdentity = userIdentity,
                qrCodeJson = qrCodeJson
            )
        }

        composable("messages/chat/{contactId}") { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
            // Use contactId as conversationId for 1:1 chats
            val conversationId = contactId
            val contactName = contactId // TODO: Resolve contact name from contacts repository

            com.void.block.messaging.ui.ChatScreen(
                conversationId = conversationId,
                contactId = contactId,
                contactName = contactName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // Contacts Block Routes
        // ═══════════════════════════════════════════════════════════════

        composable(Routes.CONTACTS_LIST) {
            com.void.block.contacts.ui.screens.ContactsListScreen(
                onNavigateToAddContact = { navController.navigate(Routes.CONTACTS_ADD) },
                onNavigateToScanQR = { navController.navigate(Routes.CONTACTS_SCAN) },
                onNavigateToContactDetail = { contactId ->
                    // Navigate to chat with selected contact
                    navController.navigate("messages/chat/$contactId")
                }
            )
        }

        composable(Routes.CONTACTS_ADD) {
            com.void.block.contacts.ui.screens.AddContactScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToScanQR = { navController.navigate(Routes.CONTACTS_SCAN) },
                onContactAdded = { contactId ->
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.CONTACTS_SCAN) {
            com.void.block.contacts.ui.screens.ScanQRScreen(
                onNavigateBack = { navController.popBackStack() },
                onContactAdded = { contactId ->
                    // Navigate to chat with new contact
                    navController.navigate("messages/chat/$contactId") {
                        popUpTo(Routes.CONTACTS_LIST) { inclusive = false }
                    }
                }
            )
        }
    }
}
