package com.rohit.sosafe.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.rohit.sosafe.data.AppMode
import com.rohit.sosafe.data.AppModeManager
import com.rohit.sosafe.data.RoleManager
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.data.contracts.SoSafeContract
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
    val lastActive: String,
    val activeSession: SosSession? = null
)

enum class ContactStatus {
    ONLINE, OFFLINE, PENDING, EMERGENCY
}

data class DashboardState(
    val userCode: String = "--- ---",
    val isProtectionActive: Boolean = false,
    val connectionStatus: String = "STABLE",
    val broadcastStatus: String = "IDLE",
    val contacts: List<Contact> = emptyList(),
    val isEmergency: Boolean = false,
    val activeEmergencySession: SosSession? = null,
    val dismissedSessions: Set<String> = emptySet()
)

class DashboardViewModel(
    private val userManager: UserManager,
    private val appModeManager: AppModeManager
) : ViewModel() {

    private val _dashboardState = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _dashboardState.asStateFlow()
    
    private val db = Firebase.firestore
    private var sessionListenerJob: ListenerRegistration? = null

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
            
            // First load contacts, then start discovery if we are a guardian
            refreshContacts {
                if (RoleManager.isGuardian()) {
                    startSessionDiscovery()
                }
            }
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

    /**
     * ROBUST DISCOVERY: Listens for sessions from ANY contact in the contact list.
     */
    private fun startSessionDiscovery() {
        val contactIds = _dashboardState.value.contacts.map { it.id }
        if (contactIds.isEmpty()) {
            Log.d("SOS_AUDIT", "Discovery: No contacts to listen for.")
            return
        }

        Log.d("SOS_AUDIT", "GUARDIAN_DISCOVERY_START: Monitoring ${contactIds.size} contacts")
        
        sessionListenerJob?.remove()
        sessionListenerJob = db.collection(SoSafeContract.Collections.SESSIONS)
            .whereIn(SoSafeContract.Fields.SENDER_ID, contactIds)
            .whereEqualTo(SoSafeContract.Fields.STATUS, SoSafeContract.Status.ACTIVE)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SOS_AUDIT", "LISTENER_ERROR: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val sessions = snapshot.documents.mapNotNull { it.toObject(SosSession::class.java) }
                    
                    // Filter out dismissed sessions
                    val activeSession = sessions.firstOrNull { session -> 
                        !_dashboardState.value.dismissedSessions.contains(session.sessionId)
                    }

                    _dashboardState.value = _dashboardState.value.copy(
                        activeEmergencySession = activeSession
                    )
                    
                    updateContactStatuses(sessions)
                } else {
                    _dashboardState.value = _dashboardState.value.copy(
                        activeEmergencySession = null
                    )
                    updateContactStatuses(emptyList())
                }
            }
    }

    private fun updateContactStatuses(activeSessions: List<SosSession>) {
        val updatedContacts = _dashboardState.value.contacts.map { contact ->
            val session = activeSessions.find { it.senderId == contact.id }
            if (session != null) {
                contact.copy(status = ContactStatus.EMERGENCY, activeSession = session)
            } else {
                contact.copy(status = ContactStatus.ONLINE, activeSession = null)
            }
        }
        _dashboardState.value = _dashboardState.value.copy(contacts = updatedContacts)
    }

    fun refreshContacts(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            val contactCodes = userManager.getContacts()
            val contacts = contactCodes.map { contactCode ->
                Contact(
                    id = contactCode,
                    name = "USER_${contactCode.take(4).uppercase()}",
                    status = ContactStatus.ONLINE,
                    lastActive = "RECENT"
                )
            }
            _dashboardState.value = _dashboardState.value.copy(contacts = contacts)
            onComplete?.invoke()
        }
    }

    fun addContact(code: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = userManager.addContact(code, RoleManager.isGuardian())
            if (result.isSuccess) {
                refreshContacts {
                    if (RoleManager.isGuardian()) {
                        startSessionDiscovery() // Restart discovery with new contact list
                    }
                }
            }
            onResult(result)
        }
    }
    
    fun dismissSession(sessionId: String) {
        val dismissed = _dashboardState.value.dismissedSessions + sessionId
        _dashboardState.value = _dashboardState.value.copy(
            dismissedSessions = dismissed,
            activeEmergencySession = null
        )
    }

    override fun onCleared() {
        super.onCleared()
        sessionListenerJob?.remove()
    }
}
