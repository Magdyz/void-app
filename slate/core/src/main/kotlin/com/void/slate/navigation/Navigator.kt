package com.void.slate.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents a navigation destination.
 */
sealed class Route {
    abstract val path: String
    
    /**
     * A screen destination.
     */
    data class Screen(
        override val path: String,
        val title: String? = null
    ) : Route()
    
    /**
     * A dialog destination.
     */
    data class Dialog(
        override val path: String,
        val dismissible: Boolean = true
    ) : Route()
    
    /**
     * A bottom sheet destination.
     */
    data class BottomSheet(
        override val path: String,
        val expandedByDefault: Boolean = false
    ) : Route()
}

/**
 * Navigation interface that blocks use to navigate.
 * This abstracts away the actual navigation implementation.
 */
interface Navigator {
    /**
     * Current navigation stack.
     */
    val backStack: StateFlow<List<String>>
    
    /**
     * Navigate to a route.
     */
    fun navigate(route: String, popUpTo: String? = null, inclusive: Boolean = false)
    
    /**
     * Navigate to a route with arguments.
     */
    fun navigate(route: String, args: Map<String, Any>)
    
    /**
     * Go back to the previous screen.
     */
    fun goBack()
    
    /**
     * Go back to a specific route.
     */
    fun goBackTo(route: String, inclusive: Boolean = false)
    
    /**
     * Clear the entire back stack and navigate to a route.
     */
    fun navigateAndClear(route: String)
}

/**
 * Default implementation of Navigator.
 * In the actual app, this wraps NavHostController.
 */
class VoidNavigator : Navigator {
    
    private val _backStack = MutableStateFlow<List<String>>(emptyList())
    override val backStack: StateFlow<List<String>> = _backStack.asStateFlow()
    
    private var navController: Any? = null  // NavHostController in actual implementation
    
    override fun navigate(route: String, popUpTo: String?, inclusive: Boolean) {
        _backStack.value = _backStack.value + route
        // Actual navigation happens via navController
    }
    
    override fun navigate(route: String, args: Map<String, Any>) {
        val routeWithArgs = buildRouteWithArgs(route, args)
        navigate(routeWithArgs)
    }
    
    override fun goBack() {
        _backStack.value = _backStack.value.dropLast(1)
    }
    
    override fun goBackTo(route: String, inclusive: Boolean) {
        val index = _backStack.value.indexOfLast { it.startsWith(route) }
        if (index >= 0) {
            val dropCount = if (inclusive) _backStack.value.size - index else _backStack.value.size - index - 1
            _backStack.value = _backStack.value.dropLast(dropCount)
        }
    }
    
    override fun navigateAndClear(route: String) {
        _backStack.value = listOf(route)
    }
    
    private fun buildRouteWithArgs(route: String, args: Map<String, Any>): String {
        var result = route
        args.forEach { (key, value) ->
            result = result.replace("{$key}", value.toString())
        }
        return result
    }
}

// ═══════════════════════════════════════════════════════════════════
// Route Constants (defined in each block, collected here for reference)
// ═══════════════════════════════════════════════════════════════════

object Routes {
    // Onboarding
    const val ONBOARDING_START = "onboarding/start"
    const val ONBOARDING_COMPLETE = "onboarding/complete"
    
    // Identity
    const val IDENTITY_GENERATE = "identity/generate"
    const val IDENTITY_DISPLAY = "identity/display"
    
    // Rhythm
    const val RHYTHM_SETUP = "rhythm/setup"
    const val RHYTHM_CONFIRM = "rhythm/confirm"
    const val RHYTHM_UNLOCK = "rhythm/unlock"
    const val RHYTHM_RECOVERY = "rhythm/recovery"

    // Constellation
    const val CONSTELLATION_AUTH_METHOD = "constellation/auth_method"
    const val CONSTELLATION_BIOMETRIC_SETUP = "constellation/biometric_setup"
    const val CONSTELLATION_SETUP = "constellation/setup"
    const val CONSTELLATION_CONFIRM = "constellation/confirm"
    const val CONSTELLATION_UNLOCK = "constellation/unlock"
    const val CONSTELLATION_RECOVERY = "constellation/recovery"

    // Messaging
    const val MESSAGES_LIST = "messages/list"
    const val MESSAGES_CHAT = "messages/chat/{contactId}"
    
    // Contacts
    const val CONTACTS_LIST = "contacts/list"
    const val CONTACTS_ADD = "contacts/add"
    const val CONTACTS_SCAN = "contacts/scan"
    
    // Decoy
    const val DECOY_HOME = "decoy/home"
    
    // Settings
    const val SETTINGS = "settings"
}
