package com.void.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.void.app.navigation.VoidNavGraph
import com.void.block.identity.domain.GenerateIdentity
import com.void.slate.crypto.CryptoProvider
import com.void.slate.design.theme.VoidTheme
import com.void.slate.navigation.Routes
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Main activity for VOID app.
 * Single Activity architecture - all screens are Composables.
 * Extends FragmentActivity to support BiometricPrompt.
 */
class MainActivity : FragmentActivity() {

    // Inject dependencies
    private val appStateManager: AppStateManager by inject()
    private val generateIdentity: GenerateIdentity by inject()
    private val cryptoProvider: CryptoProvider by inject()
    private val secureStorage: SecureStorage by inject()
    private val messageSyncEngine: com.void.block.messaging.sync.MessageSyncEngine by inject()

    /**
     * Permission launcher for POST_NOTIFICATIONS (Android 13+).
     * Registered before onCreate to handle permission requests.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("VOID_PERMISSIONS", "✅ Notification permission granted")
        } else {
            Log.d("VOID_PERMISSIONS", "⚠️  Notification permission denied - push notifications disabled")
            Log.d("VOID_PERMISSIONS", "   App will continue using polling for message delivery")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SECURITY: Clear activity notification when app opens
        // User will see message details in-app after authentication
        messageSyncEngine.clearActivityNotification()

        setContent {
            VoidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Determine start destination based on app state
                    var startDestination by remember { mutableStateOf<String?>(null) }
                    var navController: NavHostController? by remember { mutableStateOf(null) }

                    LaunchedEffect(Unit) {
                        // TODO: Re-enable verification tests in a separate test app or first-launch only
                        // Disabled for now because they call panicWipe() which deletes user data
                        // lifecycleScope.launch {
                        //     runVerificationTests()
                        // }

                        // Determine where to start the user
                        startDestination = appStateManager.getStartDestination()

                        Log.d("VOID_NAV", "═══════════════════════════════════════")
                        Log.d("VOID_NAV", "🧭 Navigation Start")
                        Log.d("VOID_NAV", "═══════════════════════════════════════")
                        Log.d("VOID_NAV", "Start Destination: $startDestination")
                        Log.d("VOID_NAV", "First Launch: ${appStateManager.isFirstLaunch()}")
                        Log.d("VOID_NAV", "Can Unlock: ${appStateManager.canUnlock()}")
                        Log.d("VOID_NAV", "═══════════════════════════════════════")
                    }

                    // Show loading until we know where to start
                    if (startDestination == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        navController = rememberNavController()
                        VoidNavGraph(
                            navController = navController!!,
                            startDestination = startDestination!!
                        )

                        // Handle deep links after navigation is set up
                        LaunchedEffect(navController) {
                            intent?.data?.let { uri ->
                                handleVoidDeepLink(navController!!, uri)
                            }
                        }

                        // Request notification permission on Android 13+ (one-time request)
                        LaunchedEffect(Unit) {
                            requestNotificationPermissionIfNeeded()
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle new intent when activity is already running (singleTop launch mode).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // SECURITY: Clear notification when app returns from background
        messageSyncEngine.clearActivityNotification()

        // Handle deep link from new intent
        intent.data?.let { uri ->
            // We'll need to pass the navController somehow
            // For now, log it - proper implementation would use a shared ViewModel
            Log.d("VOID_DEEPLINK", "New intent received with deep link: $uri")
            // TODO: Implement proper deep link handling for onNewIntent
        }
    }

    /**
     * Called when activity comes to foreground.
     * Clear notifications so user sees details in-app after auth.
     */
    override fun onResume() {
        super.onResume()

        // SECURITY: Clear activity notification when app comes to foreground
        // User will see actual message details in-app after authentication
        messageSyncEngine.clearActivityNotification()
    }

    /**
     * Handle void:// and https://void.chat deep links.
     * Supports:
     * - void://ghost.paper.forty (custom scheme)
     * - https://void.chat/c/ghost.paper.forty (app link)
     */
    private fun handleVoidDeepLink(navController: NavHostController, uri: Uri) {
        Log.d("VOID_DEEPLINK", "═══════════════════════════════════════")
        Log.d("VOID_DEEPLINK", "🔗 Deep Link Received")
        Log.d("VOID_DEEPLINK", "═══════════════════════════════════════")
        Log.d("VOID_DEEPLINK", "URI: $uri")
        Log.d("VOID_DEEPLINK", "Scheme: ${uri.scheme}")
        Log.d("VOID_DEEPLINK", "Host: ${uri.host}")
        Log.d("VOID_DEEPLINK", "Path: ${uri.path}")

        try {
            // Extract the 3-word identity
            val rawIdentity = when (uri.scheme) {
                "void" -> {
                    // void://ghost.paper.forty
                    uri.host
                }
                "https" -> {
                    // https://void.chat/c/ghost.paper.forty
                    uri.lastPathSegment
                }
                else -> {
                    Log.e("VOID_DEEPLINK", "❌ Unsupported scheme: ${uri.scheme}")
                    return
                }
            }

            Log.d("VOID_DEEPLINK", "Extracted identity: $rawIdentity")

            if (isValidIdentity(rawIdentity)) {
                Log.d("VOID_DEEPLINK", "✅ Valid identity, navigating to add contact screen")

                // Navigate to "Add Contact" screen with pre-filled ID
                navController.navigate("${Routes.CONTACTS_ADD}?id=$rawIdentity")

                Log.d("VOID_DEEPLINK", "═══════════════════════════════════════")
            } else {
                Log.e("VOID_DEEPLINK", "❌ Invalid identity format: $rawIdentity")
                Log.d("VOID_DEEPLINK", "═══════════════════════════════════════")
            }

        } catch (e: Exception) {
            Log.e("VOID_DEEPLINK", "❌ Error handling deep link: ${e.message}", e)
            Log.d("VOID_DEEPLINK", "═══════════════════════════════════════")
        }
    }

    /**
     * Validate that a string matches the 3-word identity format.
     * Format: word.word.word (e.g., ghost.paper.forty)
     */
    private fun isValidIdentity(identity: String?): Boolean {
        if (identity == null) return false

        // Split by dots
        val parts = identity.split(".")

        // Must have exactly 3 parts
        if (parts.size != 3) return false

        // Each part must be alphabetic and non-empty
        return parts.all { part ->
            part.isNotEmpty() && part.all { it.isLetter() }
        }
    }

    /**
     * Run verification tests for Phase 1A and 1B.
     * This runs in background and doesn't block the UI.
     */
    private suspend fun runVerificationTests() {
        try {
            // TEST: Verify Phase 1A - Identity Block works
            val identity = generateIdentity(regenerate = false)

            Log.d("VOID_SECURE", "══════════════════════════════════════════════════════")
            Log.d("VOID_SECURE", "✅ PHASE 1A SUCCESS: Identity Block Verified!")
            Log.d("VOID_SECURE", "══════════════════════════════════════════════════════")
            Log.d("VOID_SECURE", "3-Word ID: ${identity.formatted}")
            Log.d("VOID_SECURE", "Created At: ${identity.createdAt}")
            Log.d("VOID_SECURE", "Seed Length: ${identity.seed.size} bytes")
            Log.d("VOID_SECURE", "══════════════════════════════════════════════════════")
            Log.d("VOID_SECURE", "✨ Cryptography: WORKING")
            Log.d("VOID_SECURE", "✨ Secure Storage: WORKING")
            Log.d("VOID_SECURE", "✨ Identity Generation: WORKING")
            Log.d("VOID_SECURE", "══════════════════════════════════════════════════════")

            // TODO: Add Constellation verification tests if needed

        } catch (e: Exception) {
            Log.e("VOID_SECURE", "❌ VERIFICATION FAILED: ${e.message}", e)
        }
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     *
     * On Android 13+, apps must explicitly request this permission to show notifications.
     * This is a one-time request that shows the system permission dialog.
     *
     * If denied:
     * - Push notifications won't be shown (but will still wake the app)
     * - App falls back to polling for message delivery
     * - User can manually enable in Settings later
     *
     * IMPORTANT: This is called ONCE when the app starts. We don't spam the user
     * if they deny the permission. They can enable it in Settings if they change their mind.
     */
    private fun requestNotificationPermissionIfNeeded() {
        // Only needed on Android 13+ (TIRAMISU = API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS

            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                    Log.d("VOID_PERMISSIONS", "✅ Notification permission already granted")
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    // User previously denied, but we could show rationale
                    // For now, we'll still request it once
                    Log.d("VOID_PERMISSIONS", "📱 Requesting notification permission (user previously denied)")
                    notificationPermissionLauncher.launch(permission)
                }
                else -> {
                    // First time asking for permission
                    Log.d("VOID_PERMISSIONS", "📱 Requesting notification permission (first time)")
                    notificationPermissionLauncher.launch(permission)
                }
            }
        } else {
            // Android 12 and below - permission granted automatically
            Log.d("VOID_PERMISSIONS", "✅ Notification permission not required (Android < 13)")
        }
    }
}
