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
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject
import com.void.block.identity.data.IdentityRepository
import com.void.slate.network.push.PushRegistration

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

        // 🚀 INSTANT-VOID: Enable adaptive mode for testing
        initializeInstantVoid()

        // Initialize message sync infrastructure
        initializeSync()

        // Initialize push notification registration (Play flavor only)
        initializePushRegistration()
    }

    /**
     * Initialize INSTANT-VOID adaptive protocol.
     *
     * This enables near real-time messaging during active conversations
     * while maintaining Poisson Ghost privacy during dormant periods.
     *
     * TEMPORARY: For testing Phase 1 implementation.
     * TODO: Move to user settings UI for production.
     */
    private fun initializeInstantVoid() {
        applicationScope.launch {
            try {
                Log.d(TAG, "🚀 Initializing INSTANT-VOID adaptive protocol")

                // Get dependencies from Koin
                val settings: com.void.block.messaging.adaptive.InstantVoidSettings by inject(
                    com.void.block.messaging.adaptive.InstantVoidSettings::class.java
                )
                val stateManager: com.void.block.messaging.adaptive.ConversationStateManager by inject(
                    com.void.block.messaging.adaptive.ConversationStateManager::class.java
                )
                val pollingEngine: com.void.block.messaging.adaptive.VoidPollingEngine by inject(
                    com.void.block.messaging.adaptive.VoidPollingEngine::class.java
                )

                // Initialize state manager (load persisted states)
                stateManager.initialize()
                Log.d(TAG, "✅ ConversationStateManager initialized")

                // Enable adaptive mode with BALANCED preset
                settings.applyPreset(com.void.block.messaging.adaptive.InstantVoidPreset.BALANCED)
                Log.d(TAG, "✅ INSTANT-VOID enabled (BALANCED preset)")

                // Start adaptive polling engine
                pollingEngine.start()
                Log.d(TAG, "✅ Adaptive polling engine started")

                // Log configuration
                val config = settings.getConfig()
                Log.d(TAG, "📋 Configuration:")
                Log.d(TAG, "   - Enabled: ${config.enabled}")
                Log.d(TAG, "   - WebSocket: ${config.enableWebSocket}")
                Log.d(TAG, "   - Cover Traffic: ${config.coverTrafficEnabled}")
                Log.d(TAG, "   - Min Polling: ${config.minPollingInterval}")
                Log.d(TAG, "   - Max Polling: ${config.maxPollingInterval}")

                Log.d(TAG, "🎉 INSTANT-VOID initialization complete!")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize INSTANT-VOID: ${e.message}", e)
            }
        }
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
                val contactRepository: com.void.block.contacts.data.ContactRepository by inject(com.void.block.contacts.data.ContactRepository::class.java)

                // ✅ CRITICAL FIX: Load contacts BEFORE syncing messages
                // Messages need contacts to be decrypted (sealed sender)
                contactRepository.loadContacts()
                Log.d(TAG, "✅ Contacts loaded for message decryption")

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

                        // Register FCM token for push notifications (Play flavor only)
                        registerFcmTokenForIdentity()

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

    /**
     * Initialize push notification registration (Play flavor only).
     *
     * Self-healing mechanism that registers FCM token on every app start.
     * This ensures registration is recovered if:
     * - Token was never registered (identity created before FCM token)
     * - Registration expired or was lost
     * - Mailbox rotated but registration wasn't updated
     *
     * Gracefully handles FOSS flavor (no Firebase) without crashing.
     */
    private fun initializePushRegistration() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                // [PUSH_DEBUG] Checkpoint 0: Self-heal started
                Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_0_SELFHEAL_START")

                // Check if Firebase is available (Play flavor only)
                val firebaseClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
                val getInstance = firebaseClass.getMethod("getInstance")
                val firebaseMessaging = getInstance.invoke(null)
                val getTokenMethod = firebaseClass.getMethod("getToken")
                val tokenTask = getTokenMethod.invoke(firebaseMessaging)

                // Manually await the Task using suspendCoroutine to avoid dependency on play-services
                val token = awaitFirebaseTask(tokenTask)

                // [PUSH_DEBUG] Token acquired
                Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_0A_TOKEN_ACQUIRED | token_prefix=${token.take(10)}")

                // Register token if we have an identity
                val identityRepo: IdentityRepository by inject(IdentityRepository::class.java)
                val identity = identityRepo.getIdentity()

                if (identity != null) {
                    // [PUSH_DEBUG] Identity found, attempting registration
                    Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_0B_IDENTITY_FOUND | registering...")

                    // Get mailbox seed (NOT identity seed!) for push registration
                    val mailboxSeed = identityRepo.getMailboxSeed()
                    if (mailboxSeed == null) {
                        Log.e(TAG, "❌ FCM self-heal failed: mailbox seed not found")
                        Log.e(TAG, "[PUSH_DEBUG] CHECKPOINT_0C_SELFHEAL_FAILED | error=mailbox_seed_null")
                        return@launch
                    }

                    val pushRegistration: PushRegistration by inject(PushRegistration::class.java)
                    pushRegistration.register(mailboxSeed, token).fold(
                        onSuccess = {
                            Log.d(TAG, "✅ FCM self-heal: Token registered on app startup")
                            // [PUSH_DEBUG] Self-heal succeeded
                            Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_0C_SELFHEAL_SUCCESS | token_prefix=${token.take(10)}")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "❌ FCM self-heal failed: ${error.message}", error)
                            // [PUSH_DEBUG] Self-heal failed
                            Log.e(TAG, "[PUSH_DEBUG] CHECKPOINT_0C_SELFHEAL_FAILED | error=${error.message}")
                        }
                    )
                } else {
                    Log.d(TAG, "⚠️  FCM self-heal: No identity yet, will register after onboarding")
                    // [PUSH_DEBUG] No identity
                    Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_0B_NO_IDENTITY | will register after onboarding")
                }

            } catch (e: ClassNotFoundException) {
                // FOSS flavor - Firebase not available
                Log.d(TAG, "ℹ️  FCM not available (FOSS flavor) - using fallback polling")
                Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_0_FOSS_BUILD | no Firebase")
            } catch (e: Exception) {
                // Other errors (network, etc.) - non-critical
                Log.w(TAG, "⚠️  FCM self-heal check failed (non-critical): ${e.message}")
                // [PUSH_DEBUG] Exception during self-heal
                Log.e(TAG, "[PUSH_DEBUG] CHECKPOINT_0_EXCEPTION | ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Register FCM token for the current identity.
     *
     * Called when an identity is created to immediately register for push notifications.
     * Gracefully handles FOSS flavor without crashing.
     */
    private fun registerFcmTokenForIdentity() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                // [PUSH_DEBUG] Identity created, registering FCM
                Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_1A_IDENTITY_CREATED_REGISTER_START")

                // Check if Firebase is available (Play flavor only)
                val firebaseClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
                val getInstance = firebaseClass.getMethod("getInstance")
                val firebaseMessaging = getInstance.invoke(null)
                val getTokenMethod = firebaseClass.getMethod("getToken")
                val tokenTask = getTokenMethod.invoke(firebaseMessaging)

                // Get token asynchronously
                val token = awaitFirebaseTask(tokenTask)

                // [PUSH_DEBUG] Token acquired for new identity
                Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_1A_TOKEN_FOR_NEW_IDENTITY | token_prefix=${token.take(10)}")

                // Get identity and register
                val identityRepo: IdentityRepository by inject(IdentityRepository::class.java)
                val identity = identityRepo.getIdentity()

                if (identity != null) {
                    // Get mailbox seed (NOT identity seed!) for push registration
                    val mailboxSeed = identityRepo.getMailboxSeed()
                    if (mailboxSeed == null) {
                        Log.e(TAG, "❌ FCM registration failed: mailbox seed not found")
                        Log.e(TAG, "[PUSH_DEBUG] CHECKPOINT_1A_NEW_IDENTITY_FAILED | error=mailbox_seed_null")
                        return@launch
                    }

                    val pushRegistration: PushRegistration by inject(PushRegistration::class.java)
                    pushRegistration.register(mailboxSeed, token).fold(
                        onSuccess = {
                            Log.d(TAG, "🔔 FCM token registered for new identity")
                            Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_1A_NEW_IDENTITY_SUCCESS | token_prefix=${token.take(10)}")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "❌ FCM registration failed: ${error.message}", error)
                            Log.e(TAG, "[PUSH_DEBUG] CHECKPOINT_1A_NEW_IDENTITY_FAILED | error=${error.message}")
                        }
                    )
                }

            } catch (e: ClassNotFoundException) {
                // FOSS flavor - Firebase not available
                Log.d(TAG, "ℹ️  FCM not available (FOSS flavor) - skipping push registration")
                Log.i(TAG, "[PUSH_DEBUG] CHECKPOINT_1A_FOSS_BUILD | no Firebase")
            } catch (e: Exception) {
                Log.e(TAG, "❌ FCM registration failed: ${e.message}", e)
                Log.e(TAG, "[PUSH_DEBUG] CHECKPOINT_1A_EXCEPTION | ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Await a Firebase Task using reflection (to avoid dependency on play-services).
     *
     * Uses suspendCancellableCoroutine to manually await the Task completion.
     */
    private suspend fun awaitFirebaseTask(task: Any): String = suspendCancellableCoroutine { continuation ->
        try {
            // Get Task methods via reflection
            val taskClass = task.javaClass
            val addOnSuccessListenerMethod = taskClass.getMethod(
                "addOnSuccessListener",
                Class.forName("com.google.android.gms.tasks.OnSuccessListener")
            )
            val addOnFailureListenerMethod = taskClass.getMethod(
                "addOnFailureListener",
                Class.forName("com.google.android.gms.tasks.OnFailureListener")
            )

            // Create success listener
            val successListenerClass = Class.forName("com.google.android.gms.tasks.OnSuccessListener")
            val successListener = java.lang.reflect.Proxy.newProxyInstance(
                successListenerClass.classLoader,
                arrayOf(successListenerClass)
            ) { _, _, args ->
                val result = args[0] as String
                continuation.resumeWith(kotlin.Result.success(result))
                null
            }

            // Create failure listener
            val failureListenerClass = Class.forName("com.google.android.gms.tasks.OnFailureListener")
            val failureListener = java.lang.reflect.Proxy.newProxyInstance(
                failureListenerClass.classLoader,
                arrayOf(failureListenerClass)
            ) { _, _, args ->
                val exception = args[0] as Exception
                continuation.resumeWith(kotlin.Result.failure(exception))
                null
            }

            // Attach listeners
            addOnSuccessListenerMethod.invoke(task, successListener)
            addOnFailureListenerMethod.invoke(task, failureListener)

        } catch (e: Exception) {
            continuation.resumeWith(kotlin.Result.failure(e))
        }
    }
}
