package com.void.block.onboarding

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
 * Onboarding Block
 * 
 * Guides new users through setup:
 * - Welcome screen
 * - Identity generation
 * - Rhythm key setup
 * - Recovery phrase backup
 * - Message decay selection
 */
@Block(id = "onboarding", enabledByDefault = true)
class OnboardingBlock : BlockManifest {
    
    override val id: String = "onboarding"
    
    override val routes: List<Route> = listOf(
        Route.Screen(Routes.ONBOARDING_START, "Welcome"),
        Route.Screen(Routes.ONBOARDING_COMPLETE, "Ready"),
    )
    
    override fun Module.install() {
        // TODO: Register dependencies
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        // TODO: Set up navigation
    }
}

val onboardingModule = module {
    // Onboarding block dependencies
}
