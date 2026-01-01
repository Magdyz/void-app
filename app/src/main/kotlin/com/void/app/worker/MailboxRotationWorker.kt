package com.void.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.void.block.identity.data.IdentityRepository
import com.void.slate.network.push.PushRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.java.KoinJavaComponent.inject

/**
 * WorkManager worker for mailbox rotation checks.
 *
 * Runs daily to check if mailbox needs rotation (every 25 hours).
 * When rotation is needed, re-registers FCM token with new mailbox hash.
 *
 * Gracefully handles:
 * - FOSS flavor (no Firebase) - skips rotation
 * - No identity yet - skips rotation
 * - Network errors - retries with backoff
 */
class MailboxRotationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val identityRepository: IdentityRepository by inject(IdentityRepository::class.java)
    private val pushRegistration: PushRegistration by inject(PushRegistration::class.java)

    override suspend fun doWork(): ListenableWorker.Result {
        Log.d(TAG, "🔄 Mailbox rotation check started")

        return try {
            // Get identity - skip if no identity yet
            val identity = identityRepository.getIdentity()
            if (identity == null) {
                Log.d(TAG, "○ No identity yet - skipping rotation check")
                return ListenableWorker.Result.success()
            }

            // Check if rotation is needed
            val currentTimestamp = System.currentTimeMillis()
            val needsRotation = pushRegistration.needsRotation(currentTimestamp)

            if (!needsRotation) {
                Log.d(TAG, "○ No rotation needed yet")
                val timeUntilRotation = pushRegistration.getTimeUntilRotation(currentTimestamp)
                val hoursUntilRotation = timeUntilRotation / (1000 * 60 * 60)
                Log.d(TAG, "   Next rotation in ~$hoursUntilRotation hours")
                return ListenableWorker.Result.success()
            }

            Log.d(TAG, "🔄 Rotation needed - registering new mailbox")

            // Get FCM token (Play flavor only)
            // Use reflection to avoid compile-time dependency on Firebase
            try {
                val firebaseClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
                val getInstance = firebaseClass.getMethod("getInstance")
                val firebaseMessaging = getInstance.invoke(null)
                val getTokenMethod = firebaseClass.getMethod("getToken")
                val tokenTask = getTokenMethod.invoke(firebaseMessaging)

                // Await token using reflection
                val token = awaitFirebaseTask(tokenTask)

                // Rotate to new mailbox
                pushRegistration.rotate(identity.seed, token).fold(
                    onSuccess = {
                        Log.d(TAG, "✅ Mailbox rotated successfully")
                        Log.d(TAG, "   FCM token re-registered with new mailbox")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "❌ Mailbox rotation failed: ${error.message}", error)
                        return ListenableWorker.Result.retry()
                    }
                )

            } catch (e: ClassNotFoundException) {
                // FOSS flavor - no Firebase, skip rotation
                Log.d(TAG, "ℹ️  FCM not available (FOSS flavor) - skipping rotation")
            }

            ListenableWorker.Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Rotation check failed: ${e.message}", e)

            // Retry up to 3 times with exponential backoff
            if (runAttemptCount < 3) {
                Log.d(TAG, "   Retry attempt ${runAttemptCount + 1}/3")
                ListenableWorker.Result.retry()
            } else {
                Log.e(TAG, "   Max retries exceeded - giving up")
                ListenableWorker.Result.failure()
            }
        }
    }

    /**
     * Await a Firebase Task using reflection (to avoid dependency on play-services).
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

    companion object {
        private const val TAG = "MailboxRotationWorker"
    }
}
