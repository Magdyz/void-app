package com.void.block.rhythm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.rhythm.security.RecoveryResult
import com.void.block.rhythm.security.RhythmSecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the recovery phrase screens.
 * Handles both displaying recovery phrase and recovering from phrase.
 */
class RecoveryViewModel(
    private val securityManager: RhythmSecurityManager
) : ViewModel() {

    private val _recoveryState = MutableStateFlow<RecoveryState>(RecoveryState.Idle)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()

    /**
     * Attempt to recover access using a recovery phrase.
     */
    fun recoverFromPhrase(phrase: List<String>) {
        viewModelScope.launch {
            _recoveryState.value = RecoveryState.Recovering

            when (val result = securityManager.recoverFromPhrase(phrase)) {
                is RecoveryResult.Success -> {
                    _recoveryState.value = RecoveryState.Success(
                        needsNewRhythm = result.needsNewRhythm
                    )
                }
                is RecoveryResult.InvalidPhrase -> {
                    _recoveryState.value = RecoveryState.Error("Invalid recovery phrase")
                }
                is RecoveryResult.Error -> {
                    _recoveryState.value = RecoveryState.Error(result.message)
                }
            }
        }
    }

    fun reset() {
        _recoveryState.value = RecoveryState.Idle
    }
}

sealed class RecoveryState {
    object Idle : RecoveryState()
    object Recovering : RecoveryState()
    data class Success(val needsNewRhythm: Boolean) : RecoveryState()
    data class Error(val message: String) : RecoveryState()
}
