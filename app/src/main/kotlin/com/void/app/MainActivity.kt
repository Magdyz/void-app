package com.void.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.void.app.navigation.VoidNavGraph
import com.void.block.identity.domain.GenerateIdentity
import com.void.block.rhythm.RhythmVerification
import com.void.slate.crypto.CryptoProvider
import com.void.slate.design.theme.VoidTheme
import com.void.slate.storage.SecureStorage
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Main activity for VOID app.
 * Single Activity architecture - all screens are Composables.
 */
class MainActivity : ComponentActivity() {

    // Inject dependencies
    private val appStateManager: AppStateManager by inject()
    private val generateIdentity: GenerateIdentity by inject()
    private val cryptoProvider: CryptoProvider by inject()
    private val secureStorage: SecureStorage by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Determine start destination based on app state
                    var startDestination by remember { mutableStateOf<String?>(null) }

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
                            CircularProgressIndicator()
                        }
                    } else {
                        val navController = rememberNavController()
                        VoidNavGraph(
                            navController = navController,
                            startDestination = startDestination!!
                        )
                    }
                }
            }
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

            // TEST: Verify Phase 1B - Rhythm Security works
            RhythmVerification.verify(
                context = this@MainActivity,
                crypto = cryptoProvider,
                storage = secureStorage
            )

        } catch (e: Exception) {
            Log.e("VOID_SECURE", "❌ VERIFICATION FAILED: ${e.message}", e)
        }
    }
}
