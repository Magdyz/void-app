package app.voidapp.block.constellation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.voidapp.block.constellation.security.BiometricAuthManager
import app.voidapp.block.constellation.security.BiometricAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for authentication method selection screen.
 * Checks biometric availability and manages user selection.
 */
class AuthMethodSelectionViewModel(
    private val biometricManager: BiometricAuthManager
) : ViewModel() {

    private val _biometricAvailability = MutableStateFlow<BiometricAvailability>(BiometricAvailability.Unknown)
    val biometricAvailability: StateFlow<BiometricAvailability> = _biometricAvailability.asStateFlow()

    init {
        checkBiometricAvailability()
    }

    private fun checkBiometricAvailability() {
        viewModelScope.launch {
            _biometricAvailability.value = biometricManager.isBiometricAvailable()
            println("VOID_DEBUG: Biometric availability: ${_biometricAvailability.value}")
        }
    }
}
