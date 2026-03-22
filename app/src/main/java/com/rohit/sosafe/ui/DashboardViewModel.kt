package com.rohit.sosafe.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.utils.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class Contact(
    val id: String,
    val name: String,
    val status: ContactStatus,
    val lastActive: String
)

enum class ContactStatus {
    ONLINE, OFFLINE, PENDING
}

data class DashboardState(
    val userCode: String = "--- ---",
    val isProtectionActive: Boolean = false,
    val connectionStatus: String = "STABLE",
    val broadcastStatus: String = "IDLE",
    val contacts: List<Contact> = emptyList(),
    val isEmergency: Boolean = false
)

class DashboardViewModel(private val userManager: UserManager) : ViewModel() {

    private val _dashboardState = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    init {
        loadInitialData()
        observeServiceState()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val code = userManager.getUserCodeSync() ?: userManager.getUserCode()
            val formattedCode = if (code.length >= 6) {
                "${code.substring(0, 3)}-${code.substring(3)}"
            } else code

            _dashboardState.value = _dashboardState.value.copy(userCode = formattedCode)
            refreshContacts()
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            combine(
                ServiceState.isGuardianActive,
                ServiceState.isEmergencyActive
            ) { isGuardian, isEmergency ->
                Pair(isGuardian, isEmergency)
            }.collect { (isGuardian, isEmergency) ->
                _dashboardState.value = _dashboardState.value.copy(
                    isProtectionActive = isGuardian,
                    isEmergency = isEmergency,
                    broadcastStatus = if (isEmergency) "LIVE_FEED" else "IDLE"
                )
            }
        }
    }

    fun refreshContacts() {
        viewModelScope.launch {
            val contactCodes = userManager.getContacts()
            val mockContacts = contactCodes.mapIndexed { index, contactCode ->
                Contact(
                    id = contactCode,
                    name = "UPLINK_${contactCode.take(4)}",
                    status = when (index % 3) {
                        0 -> ContactStatus.ONLINE
                        1 -> ContactStatus.OFFLINE
                        else -> ContactStatus.PENDING
                    },
                    lastActive = if (index % 3 == 1) "${index + 1}H_AGO" else if (index % 3 == 2) "WAITING_ACK" else "WATCHING"
                )
            }
            _dashboardState.value = _dashboardState.value.copy(contacts = mockContacts)
        }
    }

    fun addContact(code: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = userManager.addContact(code)
            if (result.isSuccess) {
                refreshContacts()
            }
            onResult(result)
        }
    }
}