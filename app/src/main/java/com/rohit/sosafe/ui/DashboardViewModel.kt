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
import kotlinx.coroutines.flow.*
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
    val selectedUserRecordings: List<RecordingInfo> = emptyList(),
    val selectedPlaybackRecording: RecordingInfo? = null
)

class DashboardViewModel(
    private val userManager: UserManager,
    private val appModeManager: AppModeManager,
    private val streamingModeManager: StreamingModeManager,
    private val recordingManager: RecordingManager
) : ViewModel() {

    private val _dashboardState = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _dashboardState.asStateFlow()
    
    // Internal state flows to avoid race conditions
    private val _rawContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _activeSessions = MutableStateFlow<List<SosSession>>(emptyList())
    
    private val db = Firebase.firestore
    private var sessionListenerJob: ListenerRegistration? = null
    private var userListenerJob: ListenerRegistration? = null

    init {
        setupStateSync()
        loadInitialData()
        observeServiceState()
    }

    /**
     * REACTIVE SYNC: Combines raw contacts and active sessions into the final UI state.
     * This prevents the "status stayed like that" bug by ensuring updates are atomic.
     */
    private fun setupStateSync() {
        viewModelScope.launch {
            combine(_rawContacts, _activeSessions, _dashboardState.map { it.dismissedSessions }.distinctUntilChanged()) { contacts, sessions, dismissed ->
                val updatedContacts = contacts.map { contact ->
                    val session = sessions.find { it.senderId == contact.id }
                    if (session != null) {
                        contact.copy(status = ContactStatus.EMERGENCY, activeSession = session)
                    } else {
                        contact.copy(status = ContactStatus.ONLINE, activeSession = null)
                    }
                }
                
                val activeSession = sessions.firstOrNull { it.sessionId !in dismissed }
                
                Triple(updatedContacts, activeSession, sessions)
            }.collect { (contacts, activeSession, allSessions) ->
                _dashboardState.update { it.copy(
                    contacts = contacts,
                    activeEmergencySession = activeSession
                ) }
                Log.d("SOS_AUDIT", "STATE_SYNC: Updated ${contacts.size} contacts, Active Session: ${activeSession?.sessionId}")
            }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val code = userManager.getUserCodeSync() ?: userManager.getUserCode()
            val formattedCode = if (code.length >= 6) {
                "${code.substring(0, 3)}-${code.substring(3)}"
            } else code

            _dashboardState.update { it.copy(
                userCode = formattedCode,
                streamingMode = streamingModeManager.getStreamingMode()
            ) }
            
            observeUserContacts(code)
        }
    }

    fun setStreamingMode(mode: StreamingMode) {
        streamingModeManager.setStreamingMode(mode)
        _dashboardState.update { it.copy(streamingMode = mode) }
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
                    
                    _rawContacts.value = contacts
                    
                    if (RoleManager.isGuardian()) {
                        startSessionDiscovery(contactCodes)
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
                _dashboardState.update { it.copy(
                    isProtectionActive = isGuardian,
                    isEmergency = isEmergency,
                    broadcastStatus = if (isEmergency) "LIVE_FEED" else "IDLE"
                ) }
            }
        }
    }

    private fun startSessionDiscovery(contactIds: List<String>) {
        if (contactIds.isEmpty()) {
            _activeSessions.value = emptyList()
            sessionListenerJob?.remove()
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

                if (snapshot != null) {
                    val sessions = snapshot.documents.mapNotNull { it.toObject(SosSession::class.java) }
                    _activeSessions.value = sessions
                    Log.d("SOS_AUDIT", "SESSION_DISCOVERY_UPDATE: Found ${sessions.size} active sessions")
                }
            }
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
        _dashboardState.update { it.copy(
            dismissedSessions = it.dismissedSessions + sessionId
        ) }
    }

    fun loadRecordingsForUser(userId: String) {
        val recordings = recordingManager.getRecordingsForUser(userId)
        _dashboardState.update { it.copy(selectedUserRecordings = recordings) }
    }

    fun selectPlaybackRecording(recording: RecordingInfo?) {
        _dashboardState.update { it.copy(selectedPlaybackRecording = recording) }
    }

    override fun onCleared() {
        super.onCleared()
        sessionListenerJob?.remove()
        userListenerJob?.remove()
    }
}
