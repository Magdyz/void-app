package com.void.block.rhythm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.security.RegistrationResult
import com.void.block.rhythm.security.RhythmSecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the rhythm confirmation screen.
 * Handles pattern confirmation and registration with the security manager.
 */
class RhythmConfirmViewModel(
    private val securityManager: RhythmSecurityManager
) : ViewModel() {

    private val _state = MutableStateFlow<RhythmConfirmState>(RhythmConfirmState.Idle)
    val state: StateFlow<RhythmConfirmState> = _state.asStateFlow()

    /**
     * Called when user successfully confirms the pattern.
     * Registers the rhythm with the security manager.
     */
    fun onPatternConfirmed(pattern: RhythmPattern) {
        viewModelScope.launch {
            _state.value = RhythmConfirmState.Registering

            when (val result = securityManager.registerRealRhythm(pattern)) {
                is RegistrationResult.Success -> {
                    _state.value = RhythmConfirmState.Success(
                        recoveryPhrase = result.recoveryPhrase,
                        securityLevel = result.securityLevel
                    )
                }
                is RegistrationResult.Error -> {
                    _state.value = RhythmConfirmState.Error(result.message)
                }
            }
        }
    }

    fun reset() {
        _state.value = RhythmConfirmState.Idle
    }
}

sealed class RhythmConfirmState {
    object Idle : RhythmConfirmState()
    object Registering : RhythmConfirmState()
    data class Success(
        val recoveryPhrase: List<String>,
        val securityLevel: com.void.slate.crypto.keystore.SecurityLevel
    ) : RhythmConfirmState()
    data class Error(val message: String) : RhythmConfirmState()
}
