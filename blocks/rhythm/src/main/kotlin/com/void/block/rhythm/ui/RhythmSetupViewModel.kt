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

    fun onPatternCreated(pattern: RhythmPattern) {
        viewModelScope.launch {
            _state.value = RhythmSetupState.PatternCreated(pattern)
        }
    }

    fun reset() {
        _state.value = RhythmSetupState.Idle
    }
}

sealed class RhythmSetupState {
    object Idle : RhythmSetupState()
    data class PatternCreated(val pattern: RhythmPattern) : RhythmSetupState()
    data class Error(val message: String) : RhythmSetupState()
}
