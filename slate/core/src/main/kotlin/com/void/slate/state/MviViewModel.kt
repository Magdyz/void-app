package com.void.slate.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Marker interface for UI state.
 * All screen states implement this.
 */
interface UiState

/**
 * Marker interface for user intents/actions.
 * All user actions implement this.
 */
interface UiIntent

/**
 * Marker interface for one-time effects.
 * Effects are things like navigation, showing a toast, etc.
 */
interface UiEffect

/**
 * Base ViewModel implementing MVI pattern.
 * 
 * @param S The state type
 * @param I The intent type
 * @param E The effect type
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(
    initialState: S
) : ViewModel() {
    
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()
    
    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    
    private val intents = MutableSharedFlow<I>()
    
    init {
        viewModelScope.launch {
            intents.collect { intent ->
                handleIntent(intent)
            }
        }
    }
    
    /**
     * Process a user intent.
     */
    fun onIntent(intent: I) {
        viewModelScope.launch {
            intents.emit(intent)
        }
    }
    
    /**
     * Handle an intent and update state accordingly.
     * Subclasses implement this.
     */
    protected abstract suspend fun handleIntent(intent: I)
    
    /**
     * Update the current state.
     */
    protected fun updateState(reducer: S.() -> S) {
        _state.value = _state.value.reducer()
    }
    
    /**
     * Send a one-time effect.
     */
    protected suspend fun sendEffect(effect: E) {
        _effects.send(effect)
    }
    
    /**
     * Current state value.
     */
    protected val currentState: S
        get() = _state.value
}

/**
 * Simplified ViewModel for screens without effects.
 */
abstract class StateViewModel<S : UiState, I : UiIntent>(
    initialState: S
) : MviViewModel<S, I, Nothing>(initialState) {
    
    override suspend fun sendEffect(effect: Nothing) {
        // No effects in this ViewModel
    }
}

// ═══════════════════════════════════════════════════════════════════
// Common State Patterns
// ═══════════════════════════════════════════════════════════════════

/**
 * Represents async loading state.
 */
sealed class LoadingState<out T> {
    data object Idle : LoadingState<Nothing>()
    data object Loading : LoadingState<Nothing>()
    data class Success<T>(val data: T) : LoadingState<T>()
    data class Error(val message: String, val cause: Throwable? = null) : LoadingState<Nothing>()
    
    val isLoading: Boolean get() = this is Loading
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    
    fun getOrNull(): T? = (this as? Success)?.data
}

/**
 * Common intent for refreshing data.
 */
data object RefreshIntent : UiIntent

/**
 * Common effect for navigation.
 */
data class NavigateEffect(val route: String) : UiEffect

/**
 * Common effect for showing a message.
 */
data class ShowMessageEffect(
    val message: String,
    val type: MessageType = MessageType.INFO
) : UiEffect {
    enum class MessageType { INFO, SUCCESS, WARNING, ERROR }
}
