package com.void.app

import com.void.block.rhythm.security.RhythmSecurityManager
import com.void.slate.navigation.Routes

/**
 * Determines app state and appropriate start destination.
 *
 * Flow Decision:
 * - No identity + No rhythm → Identity Generation (first launch)
 * - Has identity + Has rhythm → Rhythm Unlock (returning user)
 * - Has identity + No rhythm → Rhythm Setup (recovered from phrase)
 */
class AppStateManager(
    private val rhythmSecurityManager: RhythmSecurityManager
) {

    /**
     * Determine the start destination based on current app state.
     */
    suspend fun getStartDestination(): String {
        return when {
            // Has rhythm setup → User needs to unlock
            rhythmSecurityManager.hasRealRhythm() -> Routes.RHYTHM_UNLOCK

            // No rhythm → First time setup (or recovered from phrase)
            else -> Routes.IDENTITY_GENERATE
        }
    }

    /**
     * Check if this is the first app launch.
     */
    suspend fun isFirstLaunch(): Boolean {
        return !rhythmSecurityManager.hasRealRhythm()
    }

    /**
     * Check if user is set up and can unlock.
     */
    suspend fun canUnlock(): Boolean {
        return rhythmSecurityManager.isSetupComplete()
    }
}
