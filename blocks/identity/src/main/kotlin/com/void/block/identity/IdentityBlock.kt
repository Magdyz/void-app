package com.void.block.identity

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.void.block.identity.data.IdentityRepository
import com.void.block.identity.domain.GenerateIdentity
import com.void.block.identity.domain.WordDictionary
import com.void.block.identity.events.IdentityCreated
import com.void.block.identity.events.IdentityRegenerated
import com.void.block.identity.ui.IdentityScreen
import com.void.block.identity.ui.IdentityViewModel
import com.void.slate.block.Block
import com.void.slate.block.BlockEvents
import com.void.slate.block.BlockManifest
import com.void.slate.event.AppStarted
import com.void.slate.navigation.Navigator
import com.void.slate.navigation.Route
import com.void.slate.navigation.Routes
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Identity Block
 * 
 * Provides the 3-word identity system.
 * 
 * This block is completely self-contained:
 * - UI: IdentityScreen
 * - State: IdentityViewModel
 * - Domain: GenerateIdentity, WordDictionary
 * - Data: IdentityRepository
 * - Events: IdentityCreated, IdentityRegenerated
 */
@Block(
    id = "identity",
    enabledByDefault = true
)
class IdentityBlock : BlockManifest {
    
    override val id: String = "identity"
    
    override val routes: List<Route> = listOf(
        Route.Screen(Routes.IDENTITY_GENERATE, "Generate Identity"),
        Route.Screen(Routes.IDENTITY_DISPLAY, "Your Identity"),
    )
    
    override val events = BlockEvents(
        emits = listOf(
            IdentityCreated::class,
            IdentityRegenerated::class
        ),
        observes = listOf(
            AppStarted::class
        )
    )
    
    override fun Module.install() {
        // Data layer
        single {
            IdentityRepository(
                secureStorage = get(),
                crypto = get(),
                keystoreManager = get()
            )
        }

        // Domain layer
        single { WordDictionary() }
        single { GenerateIdentity(get(), get(), get()) }

        // UI layer
        viewModel { IdentityViewModel(get(), get()) }
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        composable(Routes.IDENTITY_GENERATE) {
            IdentityScreen(
                onComplete = { navigator.navigate(Routes.RHYTHM_SETUP) },
                onSkip = null  // Can't skip identity generation
            )
        }
        
        composable(Routes.IDENTITY_DISPLAY) {
            IdentityScreen(
                onComplete = { navigator.goBack() },
                onSkip = null
            )
        }
    }
}

/**
 * Koin module for this block.
 * Can be used for manual registration if not using auto-discovery.
 */
val identityModule = module {
    single {
        IdentityRepository(
            secureStorage = get(),
            crypto = get(),
            keystoreManager = get()
        )
    }
    single { WordDictionary() }
    single { GenerateIdentity(get(), get(), get()) }
    viewModel { IdentityViewModel(get(), get()) }
}
