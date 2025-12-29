package com.void.block.rhythm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.rhythm.domain.RhythmPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the rhythm setup screen.
 * Manages the state during rhythm pattern creation.
 */
class RhythmSetupViewModel : ViewModel() {

    private val _state = MutableStateFlow<RhythmSetupState>(RhythmSetupState.Idle)
    val state: StateFlow<RhythmSetupState> = _state.asStateFlow()

    init {
        println("VOID_DEBUG: RhythmSetupViewModel initialized, initial state: ${_state.value}")
    }

    fun onPatternCreated(pattern: RhythmPattern) {
        println("VOID_DEBUG: RhythmSetupViewModel.onPatternCreated called with pattern: ${pattern.intervals}")
        viewModelScope.launch {
            _state.value = RhythmSetupState.PatternCreated(pattern)
            println("VOID_DEBUG: RhythmSetupViewModel state updated to: PatternCreated")
        }
    }

    fun reset() {
        println("VOID_DEBUG: RhythmSetupViewModel.reset called")
        _state.value = RhythmSetupState.Idle
    }
}

sealed class RhythmSetupState {
    object Idle : RhythmSetupState()
    data class PatternCreated(val pattern: RhythmPattern) : RhythmSetupState()
    data class Error(val message: String) : RhythmSetupState()
}
