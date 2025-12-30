package com.void.app.di

import android.content.Context
import com.void.app.AppStateManager
import com.void.app.RuntimeFeatureFlags
import com.void.app.crypto.AppMessageEncryptionService
import com.void.block.messaging.crypto.MessageEncryptionService
import com.void.block.messaging.sync.MessageSyncEngine
import com.void.block.contacts.contactsModule
// import com.void.block.decoy.decoyModule
import com.void.block.identity.identityModule
import com.void.block.messaging.messagingModule
// import com.void.block.onboarding.onboardingModule
import com.void.block.rhythm.rhythmModule
import com.void.slate.block.BlockRegistry
import com.void.slate.block.FeatureFlags
import com.void.slate.crypto.CryptoProvider
import com.void.slate.crypto.impl.TinkCryptoProvider
import com.void.slate.event.EventBus
import com.void.slate.event.InMemoryEventBus
import com.void.slate.event.LoggingEventBus
import com.void.slate.navigation.Navigator
import com.void.slate.navigation.VoidNavigator
import com.void.slate.crypto.keystore.KeystoreManager
import com.void.slate.network.di.networkModule
import com.void.slate.storage.SecureStorage
import com.void.slate.storage.impl.SqlCipherStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/**
 * Main application DI module.
 * 
 * This is the ONLY place where all blocks are wired together.
 * Each block has its own module that registers its internal dependencies.
 */
val appModule = module {
    
    // ═══════════════════════════════════════════════════════════════════
    // Slate Infrastructure (shared across all blocks)
    // ═══════════════════════════════════════════════════════════════════
    
    // Event Bus - the connector between blocks
    single<EventBus> { 
        LoggingEventBus(
            delegate = InMemoryEventBus(),
            logger = { message -> android.util.Log.d("EventBus", message) }
        )
    }
    
    // Navigator - handles all navigation
    single<Navigator> { VoidNavigator() }

    // Feature Flags - controls block availability
    single<FeatureFlags> { RuntimeFeatureFlags() }

    // Crypto Provider - cryptographic operations
    single<CryptoProvider> { TinkCryptoProvider() }

    // Secure Storage - encrypted storage backend
    single<SecureStorage> { SqlCipherStorage(get<Context>()) }

    // Keystore Manager - hardware-backed key storage
    single { KeystoreManager(get<Context>()) }

    // Message Encryption Service - bridges blocks for secure messaging
    // This lives in app module because it needs access to both identity and contacts
    single<MessageEncryptionService> {
        AppMessageEncryptionService(
            messageEncryption = get(),
            identityRepository = get(),
            contactRepository = get()
        )
    }

    // Crypto Debug Helper - for diagnosing encryption issues
    // TODO: Remove in production builds
    single {
        com.void.app.debug.CryptoDebugHelper(
            identityRepository = get(),
            contactRepository = get()
        )
    }

    // App State Manager - determines navigation flow
    single { AppStateManager(rhythmSecurityManager = get()) }

    // ═══════════════════════════════════════════════════════════════════
    // Message Sync Infrastructure (for push notifications and background sync)
    // ═══════════════════════════════════════════════════════════════════

    // Application-level CoroutineScope for MessageSyncEngine
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // MessageSyncEngine - handles WebSocket sync and notifications
    single {
        MessageSyncEngine(
            context = get(),
            networkClient = get(),
            messageRepository = get(),
            encryptionService = get(),
            scope = get()
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // Block Modules (each block registers its own dependencies)
    // ═══════════════════════════════════════════════════════════════════

    // Include all block modules
    // These are loaded dynamically based on which blocks are included in build
    includes(
        networkModule,    // Network infrastructure (slate module)
        identityModule,
        rhythmModule,
        messagingModule,  // Phase 2: Messaging
        contactsModule,   // Phase 2: Contacts
        // decoyModule,
        // onboardingModule,
    )
}

/**
 * Test module that replaces real implementations with fakes.
 */
val testModule = module {
    single<EventBus> { InMemoryEventBus() }
    single<Navigator> { VoidNavigator() }
    single<FeatureFlags> { FeatureFlags.AllEnabled }
    single { BlockRegistry(get<FeatureFlags>()) }
}
