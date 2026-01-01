package com.void.app

import android.app.Application
import android.util.Log
import com.void.app.di.appModule
import com.void.slate.event.observe
import com.void.slate.network.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject

/**
 * VOID Application
 *
 * The app shell is MINIMAL - it just:
 * 1. Initializes Koin
 * 2. Sets up core infrastructure
 * 3. Initializes background sync
 *
 * All actual logic lives in blocks.
 */
class VoidApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "VoidApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize DI
        startKoin {
            androidContext(this@VoidApp)
            modules(appModule)
        }

        // Initialize message sync infrastructure
        initializeSync()
    }

    /**
     * Initialize background sync infrastructure.
     * This ensures messages are received even when app is closed.
     *
     * Note: Push notification registration happens automatically in VoidFirebaseService
     * when FCM tokens are generated/refreshed (Play flavor only).
     */
    private fun initializeSync() {
        applicationScope.launch {
            try {
                Log.d(TAG, "🚀 Initializing message sync infrastructure")

                // Get dependencies from Koin
                val syncScheduler: SyncScheduler by inject(SyncScheduler::class.java)
                val eventBus: com.void.slate.event.EventBus by inject(com.void.slate.event.EventBus::class.java)

                // Schedule periodic sync as fallback (runs every 6 hours)
                // This ensures messages are delivered even if push notifications fail
                syncScheduler.schedulePeriodicSync()
                Log.d(TAG, "✅ Periodic sync scheduled")

                // Schedule mailbox rotation checks (runs daily)
                // This rotates the mailbox hash for privacy
                syncScheduler.scheduleRotationCheck()
                Log.d(TAG, "✅ Mailbox rotation checks scheduled")

                // Trigger immediate sync to fetch any pending messages
                // Note: This may fail if user doesn't have an identity yet (during onboarding)
                syncScheduler.triggerImmediateSync()
                Log.d(TAG, "✅ Immediate sync triggered")

                // ✅ FIX: Listen for IdentityCreated event to trigger sync after onboarding
                // When a new identity is created, we need to fetch any messages sent to that mailbox
                applicationScope.launch {
                    eventBus.observe<com.void.block.identity.events.IdentityCreated>().collect { event ->
                        Log.d(TAG, "🎉 Identity created: ${event.identityFormatted}")
                        Log.d(TAG, "   Triggering immediate sync for new identity...")
                        syncScheduler.triggerImmediateSync()
                        Log.d(TAG, "   ✅ Sync triggered for new identity")
                    }
                }

                Log.d(TAG, "✅ Message sync infrastructure initialized")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize sync: ${e.message}", e)
            }
        }
    }
}
