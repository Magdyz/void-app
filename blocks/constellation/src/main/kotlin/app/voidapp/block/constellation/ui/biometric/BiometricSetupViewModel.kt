package app.voidapp.block.constellation.ui.biometric

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.voidapp.block.constellation.security.BiometricAuthManager
import app.voidapp.block.constellation.security.BiometricAuthResult
import app.voidapp.block.constellation.security.BiometricSetupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for biometric setup screen.
 * Handles biometric enrollment flow.
 */
class BiometricSetupViewModel(
    private val biometricManager: BiometricAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow<BiometricSetupState>(BiometricSetupState.Initial)
    val state: StateFlow<BiometricSetupState> = _state.asStateFlow()

    fun startBiometricEnrollment(activity: FragmentActivity) {
        println("VOID_DEBUG: BiometricSetupViewModel.startBiometricEnrollment called")
        viewModelScope.launch {
            println("VOID_DEBUG: Setting state to Authenticating")
            _state.value = BiometricSetupState.Authenticating

            // First, enable biometric (creates Keystore key)
            println("VOID_DEBUG: Calling biometricManager.enableBiometric()")
            when (val setupResult = biometricManager.enableBiometric()) {
                is BiometricSetupResult.Success -> {
                    println("VOID_DEBUG: Biometric enabled successfully, now authenticating")
                    // Now authenticate to confirm it works
                    when (val authResult = biometricManager.authenticateWithBiometric(activity)) {
                        is BiometricAuthResult.Success -> {
                            println("VOID_DEBUG: Biometric authentication successful")
                            _state.value = BiometricSetupState.Success
                        }
                        is BiometricAuthResult.Cancelled -> {
                            println("VOID_DEBUG: Biometric authentication cancelled")
                            _state.value = BiometricSetupState.Error("Setup cancelled")
                        }
                        is BiometricAuthResult.Failed -> {
                            println("VOID_DEBUG: Biometric authentication failed: ${authResult.error}")
                            _state.value = BiometricSetupState.Error(authResult.error)
                        }
                        else -> {
                            println("VOID_DEBUG: Biometric authentication unexpected result: $authResult")
                            _state.value = BiometricSetupState.Error("Biometric authentication failed")
                        }
                    }
                }
                is BiometricSetupResult.NotAvailable -> {
                    println("VOID_DEBUG: Biometric not available: ${setupResult.reason}")
                    _state.value = BiometricSetupState.Error("Biometric not available: ${setupResult.reason}")
                }
                is BiometricSetupResult.Failed -> {
                    println("VOID_DEBUG: Biometric enable failed: ${setupResult.error}")
                    _state.value = BiometricSetupState.Error(setupResult.error)
                }
            }
        }
    }
}

sealed class BiometricSetupState {
    object Initial : BiometricSetupState()
    object Authenticating : BiometricSetupState()
    object Success : BiometricSetupState()
    data class Error(val message: String) : BiometricSetupState()
}
