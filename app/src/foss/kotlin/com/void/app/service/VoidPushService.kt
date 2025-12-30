package com.void.app.service

import android.util.Log

/**
 * VoidPushService - UnifiedPush receiver for F-Droid builds (STUB).
 *
 * This is a placeholder for the FOSS flavor that doesn't depend on Google Play Services.
 * UnifiedPush allows users to choose their own push notification provider.
 *
 * TODO: Implement UnifiedPush integration
 * - Register with UnifiedPush distributor (e.g., ntfy, NextPush, etc.)
 * - Receive wake-up notifications via UnifiedPush
 * - Trigger MessageSyncWorker to fetch messages
 *
 * Architecture:
 * - User installs a UnifiedPush distributor app (e.g., ntfy)
 * - Void registers with the distributor
 * - Void server sends tickles via the distributor
 * - This service receives tickles and triggers sync
 *
 * For now, this is a stub. The FOSS flavor will compile and run,
 * but won't receive push notifications until UnifiedPush is implemented.
 */
class VoidPushService {

    companion object {
        private const val TAG = "VoidPushService"
    }

    init {
        Log.d(TAG, "⚠️  FOSS build: UnifiedPush not yet implemented")
        Log.d(TAG, "Push notifications will not work in this build")
        Log.d(TAG, "TODO: Implement UnifiedPush integration")
    }

    // TODO: Implement UnifiedPush integration
    // See: https://unifiedpush.org/developers/android/
}
