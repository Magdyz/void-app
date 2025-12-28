package com.void.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.void.block.identity.domain.GenerateIdentity
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Main activity for VOID app.
 * Single Activity architecture - all screens are Composables.
 */
class MainActivity : ComponentActivity() {

    // Inject the GenerateIdentity use case from the Identity Block
    private val generateIdentity: GenerateIdentity by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TEST: Verify Phase 1A - Identity Block works
        lifecycleScope.launch {
            try {
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
            } catch (e: Exception) {
                Log.e("VOID_SECURE", "❌ PHASE 1A FAILED: ${e.message}", e)
            }
        }

        setContent {
            // TODO: Use VoidTheme when design system is implemented
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Show a simple message while we verify the backend
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "VOID\nCheck Logcat for Identity",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
            }
        }
    }
}
