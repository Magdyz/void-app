package com.void.app

import app.voidapp.block.constellation.security.ConstellationSecurityManager
import com.void.slate.navigation.Routes

/**
 * Determines app state and appropriate start destination.
 *
 * Flow Decision:
 * - No identity + No constellation → Identity Generation (first launch)
 * - Has identity + Has constellation → Constellation Unlock (returning user)
 * - Has identity + No constellation → Constellation Setup (recovered from phrase)
 */
class AppStateManager(
    private val constellationSecurityManager: ConstellationSecurityManager
) {

    /**
     * Determine the start destination based on current app state.
     */
    suspend fun getStartDestination(): String {
        return when {
            // Has constellation setup → User needs to unlock
            constellationSecurityManager.hasRealConstellation() -> Routes.CONSTELLATION_UNLOCK

            // No constellation → First time setup (or recovered from phrase)
            else -> Routes.IDENTITY_GENERATE
        }
    }

    /**
     * Check if this is the first app launch.
     */
    suspend fun isFirstLaunch(): Boolean {
        return !constellationSecurityManager.hasRealConstellation()
    }

    /**
     * Check if user is set up and can unlock.
     */
    suspend fun canUnlock(): Boolean {
        return constellationSecurityManager.isSetupComplete()
    }
}
