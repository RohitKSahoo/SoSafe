package com.rohit.sosafe.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.rohit.sosafe.data.*
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.data.contracts.SoSafeContract
import com.rohit.sosafe.utils.RecordingInfo
import com.rohit.sosafe.utils.RecordingManager
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
    val dismissedSessions: Set<String> = emptySet(),
    val streamingMode: StreamingMode = StreamingMode.HYBRID,
    val selectedUserRecordings: List<RecordingInfo> = emptyList()
)

class DashboardViewModel(
    private val userManager: UserManager,
    private val appModeManager: AppModeManager,
    private val streamingModeManager: StreamingModeManager,
    private val recordingManager: RecordingManager
) : ViewModel() {

    private val _dashboardState = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _dashboardState.asStateFlow()
    
    private val db = Firebase.firestore
    private var sessionListenerJob: ListenerRegistration? = null
    private var userListenerJob: ListenerRegistration? = null

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

            _dashboardState.value = _dashboardState.value.copy(
                userCode = formattedCode,
                streamingMode = streamingModeManager.getStreamingMode()
            )
            
            // Start real-time observation of user document for contact changes
            observeUserContacts(code)
        }
    }

    fun setStreamingMode(mode: StreamingMode) {
        streamingModeManager.setStreamingMode(mode)
        _dashboardState.value = _dashboardState.value.copy(streamingMode = mode)
    }

    private fun observeUserContacts(userCode: String) {
        userListenerJob?.remove()
        userListenerJob = db.collection(SoSafeContract.Collections.USERS)
            .document(userCode)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SOS_AUDIT", "USER_LISTENER_ERROR: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val contactCodes = snapshot.get(SoSafeContract.Fields.CONTACTS) as? List<String> ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    val contactNames = snapshot.get(SoSafeContract.Fields.CONTACT_NAMES) as? Map<String, String> ?: emptyMap()
                    
                    val contacts = contactCodes.map { contactCode ->
                        Contact(
                            id = contactCode,
                            name = contactNames[contactCode] ?: "USER_${contactCode.take(4).uppercase()}",
                            status = ContactStatus.ONLINE,
                            lastActive = "RECENT"
                        )
                    }
                    
                    _dashboardState.value = _dashboardState.value.copy(contacts = contacts)
                    
                    // Re-evaluate session discovery if contacts changed
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
            sessionListenerJob?.remove()
            _dashboardState.value = _dashboardState.value.copy(activeEmergencySession = null)
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

    fun addContact(code: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = userManager.addContact(code, RoleManager.isGuardian())
            onResult(result)
        }
    }

    fun renameContact(id: String, newName: String) {
        viewModelScope.launch {
            userManager.updateContactName(id, newName)
        }
    }
    
    fun dismissSession(sessionId: String) {
        val dismissed = _dashboardState.value.dismissedSessions + sessionId
        _dashboardState.value = _dashboardState.value.copy(
            dismissedSessions = dismissed,
            activeEmergencySession = null
        )
    }

    fun loadRecordingsForUser(userId: String) {
        val recordings = recordingManager.getRecordingsForUser(userId)
        _dashboardState.value = _dashboardState.value.copy(selectedUserRecordings = recordings)
    }

    override fun onCleared() {
        super.onCleared()
        sessionListenerJob?.remove()
        userListenerJob?.remove()
    }
}
