package com.void.block.rhythm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.void.block.rhythm.domain.RhythmPattern
import com.void.block.rhythm.security.RhythmSecurityManager
import com.void.block.rhythm.security.UnlockMode
import com.void.block.rhythm.security.UnlockResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the rhythm unlock screen.
 * Handles unlock attempts and lockout state management.
 */
class RhythmUnlockViewModel(
    private val securityManager: RhythmSecurityManager
) : ViewModel() {

    private val _unlockResult = MutableStateFlow<UnlockResult?>(null)
    val unlockResult: StateFlow<UnlockResult?> = _unlockResult.asStateFlow()

    private val _unlockSuccess = MutableStateFlow<UnlockMode?>(null)
    val unlockSuccess: StateFlow<UnlockMode?> = _unlockSuccess.asStateFlow()

    /**
     * Attempt to unlock with the given rhythm pattern.
     */
    fun attemptUnlock(pattern: RhythmPattern) {
        viewModelScope.launch {
            when (val result = securityManager.unlock(pattern)) {
                is UnlockResult.Success -> {
                    _unlockResult.value = result
                    _unlockSuccess.value = result.mode
                }
                is UnlockResult.Failed -> {
                    _unlockResult.value = result
                    // Clear after showing error briefly
                    delay(2000)
                    _unlockResult.value = null
                }
                is UnlockResult.LockedOut -> {
                    _unlockResult.value = result
                    startLockoutCountdown(result.remainingSeconds)
                }
            }
        }
    }

    private fun startLockoutCountdown(seconds: Int) {
        viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000)
                remaining--
                _unlockResult.value = UnlockResult.LockedOut(remaining)
            }
            _unlockResult.value = null
        }
    }

    fun clearUnlockSuccess() {
        _unlockSuccess.value = null
    }
}
