package com.void.block.decoy

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.void.slate.block.Block
import com.void.slate.block.BlockManifest
import com.void.slate.navigation.Navigator
import com.void.slate.navigation.Route
import com.void.slate.navigation.Routes
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Decoy Block
 * 
 * Provides plausible deniability through a decoy mode:
 * - Alternate rhythm key opens decoy
 * - Pixel-perfect clone of real UI
 * - Empty or fake data
 * - Timing attack prevention
 */
@Block(
    id = "decoy",
    flag = "feature.decoy.enabled",
    enabledByDefault = true
)
class DecoyBlock : BlockManifest {
    
    override val id: String = "decoy"
    
    override val routes: List<Route> = listOf(
        Route.Screen(Routes.DECOY_HOME, "Decoy Home"),
    )
    
    override fun Module.install() {
        // TODO: Register dependencies
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        // TODO: Set up navigation
    }
}

val decoyModule = module {
    // Decoy block dependencies
}
