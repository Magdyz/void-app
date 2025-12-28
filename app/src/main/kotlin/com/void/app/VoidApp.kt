package com.void.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.void.app.di.appModule
import com.void.app.navigation.VoidNavGraph
import com.void.slate.block.BlockRegistry
import com.void.slate.design.theme.VoidTheme
import com.void.slate.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * VOID Application
 * 
 * The app shell is MINIMAL - it just:
 * 1. Initializes Koin
 * 2. Discovers and registers blocks
 * 3. Sets up navigation
 * 
 * All actual logic lives in blocks.
 */
class VoidApp : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize DI
        startKoin {
            androidContext(this@VoidApp)
            modules(appModule)
        }
        
        // Discover and register blocks
        applicationScope.launch {
            val registry = BlockRegistry()
            val blocks = BlockLoader.discover()
            registry.register(*blocks.toTypedArray())
        }
    }
}

/**
 * Main Activity - Single Activity architecture
 * 
 * This is the ONLY Activity. All screens are Compose destinations
 * provided by blocks.
 */
class MainActivity : ComponentActivity() {
    
    private val blockRegistry: BlockRegistry by inject()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            VoidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VoidNavGraph(blockRegistry = blockRegistry)
                }
            }
        }
    }
}

/**
 * Main navigation graph that assembles routes from all blocks.
 */
@Composable
fun VoidNavGraph(blockRegistry: BlockRegistry) {
    val navController = rememberNavController()
    val navigator = remember { VoidNavigatorImpl(navController) }
    
    NavHost(
        navController = navController,
        startDestination = determineStartDestination(blockRegistry)
    ) {
        // Each block registers its own routes
        blockRegistry.getAllBlocks().forEach { block ->
            block.apply {
                routes(navigator)
            }
        }
    }
}

/**
 * Determine where to start based on app state.
 */
private fun determineStartDestination(blockRegistry: BlockRegistry): String {
    // Check if onboarding is complete
    val hasIdentity = false // TODO: Check repository
    
    return if (hasIdentity) {
        Routes.RHYTHM_UNLOCK
    } else {
        Routes.ONBOARDING_START
    }
}
