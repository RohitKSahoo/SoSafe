package com.rohit.sosafe.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceState {
    private val _isEmergencyActive = MutableStateFlow(false)
    val isEmergencyActive: StateFlow<Boolean> = _isEmergencyActive.asStateFlow()

    private val _isGuardianActive = MutableStateFlow(false)
    val isGuardianActive: StateFlow<Boolean> = _isGuardianActive.asStateFlow()

    fun setEmergencyActive(active: Boolean) {
        _isEmergencyActive.value = active
    }

    fun setGuardianActive(active: Boolean) {
        _isGuardianActive.value = active
    }
}
