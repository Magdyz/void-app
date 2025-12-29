package com.void.block.identity.ui

import com.void.block.identity.domain.GenerateIdentity
import com.void.block.identity.domain.Identity
import com.void.block.identity.domain.WordDictionary
import com.void.block.identity.events.IdentityCreated
import com.void.block.identity.events.IdentityRegenerated
import com.void.slate.event.EventBus
import com.void.slate.state.LoadingState
import com.void.slate.state.MviViewModel
import com.void.slate.state.UiEffect
import com.void.slate.state.UiIntent
import com.void.slate.state.UiState

/**
 * ViewModel for the Identity screen.
 * Follows MVI pattern with unidirectional data flow.
 */
class IdentityViewModel(
    private val generateIdentity: GenerateIdentity,
    private val eventBus: EventBus
) : MviViewModel<IdentityState, IdentityIntent, IdentityEffect>(
    initialState = IdentityState()
) {
    
    init {
        println("VOID_DEBUG: IdentityViewModel initialized")
        // Generate identity on start if not already done
        onIntent(IdentityIntent.LoadIdentity)
    }
    
    override suspend fun handleIntent(intent: IdentityIntent) {
        println("VOID_DEBUG: ViewModel received intent: $intent")
        when (intent) {
            is IdentityIntent.LoadIdentity -> loadIdentity()
            is IdentityIntent.Regenerate -> regenerateIdentity()
            is IdentityIntent.Confirm -> confirmIdentity()
            is IdentityIntent.CopyToClipboard -> copyToClipboard()
        }
    }
    
    private suspend fun loadIdentity() {
        println("VOID_DEBUG: loadIdentity() called")
        updateState { copy(loadingState = LoadingState.Loading) }

        try {
            println("VOID_DEBUG: Calling generateIdentity(regenerate=false)")
            val identity = generateIdentity(regenerate = false)
            println("VOID_DEBUG: Identity generated: ${identity.formatted}")
            updateState {
                copy(
                    loadingState = LoadingState.Success(identity),
                    identity = identity,
                    isNewIdentity = true
                )
            }
            println("VOID_DEBUG: State updated with identity. canConfirm=${currentState.canConfirm}")

            // Emit event for other blocks
            eventBus.emit(IdentityCreated(identity.formatted))

        } catch (e: Exception) {
            println("VOID_DEBUG: Failed to generate identity: ${e.message}")
            e.printStackTrace()
            updateState {
                copy(loadingState = LoadingState.Error(e.message ?: "Failed to generate identity"))
            }
        }
    }
    
    private suspend fun regenerateIdentity() {
        val oldIdentity = currentState.identity?.formatted ?: return
        
        updateState { copy(isRegenerating = true) }
        
        try {
            val newIdentity = generateIdentity(regenerate = true)
            updateState { 
                copy(
                    identity = newIdentity,
                    isRegenerating = false,
                    regenerateCount = regenerateCount + 1
                )
            }
            
            // Emit event for other blocks
            eventBus.emit(IdentityRegenerated(oldIdentity, newIdentity.formatted))
            
        } catch (e: Exception) {
            updateState { copy(isRegenerating = false) }
            sendEffect(IdentityEffect.ShowError("Failed to regenerate: ${e.message}"))
        }
    }
    
    private suspend fun confirmIdentity() {
        println("VOID_DEBUG: Processing Confirm Identity. Current identity: ${currentState.identity}")
        
        if (currentState.identity == null) {
            println("VOID_DEBUG: confirmIdentity ABORTED - Identity is null")
            return
        }

        updateState { copy(isConfirmed = true) }
        println("VOID_DEBUG: Sending NavigateToNext effect")
        sendEffect(IdentityEffect.NavigateToNext)
    }
    
    private suspend fun copyToClipboard() {
        val identity = currentState.identity ?: return
        sendEffect(IdentityEffect.CopyToClipboard(identity.formatted))
    }
}

/**
 * UI State for the Identity screen.
 */
data class IdentityState(
    val loadingState: LoadingState<Identity> = LoadingState.Idle,
    val identity: Identity? = null,
    val isNewIdentity: Boolean = false,
    val isRegenerating: Boolean = false,
    val isConfirmed: Boolean = false,
    val regenerateCount: Int = 0
) : UiState {
    
    val canRegenerate: Boolean
        get() = !isRegenerating && identity != null
    
    val canConfirm: Boolean
        get() = identity != null && !isRegenerating
}

/**
 * User intents for the Identity screen.
 */
sealed class IdentityIntent : UiIntent {
    data object LoadIdentity : IdentityIntent()
    data object Regenerate : IdentityIntent()
    data object Confirm : IdentityIntent()
    data object CopyToClipboard : IdentityIntent()
}

/**
 * One-time effects for the Identity screen.
 */
sealed class IdentityEffect : UiEffect {
    data object NavigateToNext : IdentityEffect()
    data class CopyToClipboard(val text: String) : IdentityEffect()
    data class ShowError(val message: String) : IdentityEffect()
}
