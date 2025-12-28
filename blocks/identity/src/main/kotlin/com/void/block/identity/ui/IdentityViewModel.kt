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
        // Generate identity on start if not already done
        onIntent(IdentityIntent.LoadIdentity)
    }
    
    override suspend fun handleIntent(intent: IdentityIntent) {
        when (intent) {
            is IdentityIntent.LoadIdentity -> loadIdentity()
            is IdentityIntent.Regenerate -> regenerateIdentity()
            is IdentityIntent.Confirm -> confirmIdentity()
            is IdentityIntent.CopyToClipboard -> copyToClipboard()
        }
    }
    
    private suspend fun loadIdentity() {
        updateState { copy(loadingState = LoadingState.Loading) }
        
        try {
            val identity = generateIdentity(regenerate = false)
            updateState { 
                copy(
                    loadingState = LoadingState.Success(identity),
                    identity = identity,
                    isNewIdentity = true
                )
            }
            
            // Emit event for other blocks
            eventBus.emit(IdentityCreated(identity.formatted))
            
        } catch (e: Exception) {
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
        if (currentState.identity == null) return
        
        updateState { copy(isConfirmed = true) }
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
