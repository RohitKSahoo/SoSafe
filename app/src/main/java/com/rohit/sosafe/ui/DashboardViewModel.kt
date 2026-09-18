package com.rohit.sosafe.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rohit.sosafe.architecture.AudioPlaybackController
import com.rohit.sosafe.architecture.SessionController
import com.rohit.sosafe.architecture.SessionState
import com.rohit.sosafe.data.*
import com.rohit.sosafe.data.contracts.*
import com.rohit.sosafe.data.supabase.SupabaseApi
import com.rohit.sosafe.data.supabase.SupabaseRealtimeClient
import com.rohit.sosafe.utils.RecordingInfo
import com.rohit.sosafe.utils.RecordingManager
import com.rohit.sosafe.data.RoleManager
import com.rohit.sosafe.utils.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class DashboardViewModel(
    private val userManager: UserManager,
    private val appModeManager: AppModeManager,
    private val streamingModeManager: StreamingModeManager,
    private val recordingManager: RecordingManager,
    private val networkMonitor: com.rohit.sosafe.utils.NetworkMonitor
) : ViewModel() {

    private val _dashboardState = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _dashboardState.asStateFlow()
    
    private val _rawContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _activeSessions = MutableStateFlow<List<SosSession>>(emptyList())
    
    private var userRealtime: SupabaseRealtimeClient? = null
    private var pairingRealtime: SupabaseRealtimeClient? = null
    private var removalRealtime: SupabaseRealtimeClient? = null
    private var pairingNotificationRealtime: SupabaseRealtimeClient? = null
    private var sessionsRealtime: SupabaseRealtimeClient? = null
    private var targetedPairingRequestId: String? = null

    init {
        observeNetworkState()
        setupStateSync()
        loadInitialData()
        observeServiceState()
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            networkMonitor.networkStatusFlow.collect { status ->
                _dashboardState.update { currentState ->
                    currentState.copy(
                        connectionStatus = if (status.isConnected) "CONNECTED" else "OFFLINE",
                        isNetworkConnected = status.isConnected,
                        networkQuality = status.voiceQualityStatus,
                        networkType = status.connectionType
                    )
                }
            }
        }
    }

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
            observePairingRequests(code)
            observeRemovalNotifications(code)
            observePairingNotifications(code)
        }
    }

    fun setStreamingMode(mode: StreamingMode) {
        streamingModeManager.setStreamingMode(mode)
        _dashboardState.update { it.copy(streamingMode = mode) }
    }

    private fun observeUserContacts(userCode: String) {
        userRealtime?.stop()
        
        // Initial fetch
        viewModelScope.launch(Dispatchers.IO) {
            fetchUserContacts(userCode)
        }

        userRealtime = SupabaseRealtimeClient("users", "user_id", userCode) { type, record ->
            viewModelScope.launch(Dispatchers.IO) {
                fetchUserContacts(userCode)
            }
        }.apply { start() }
    }

    private fun fetchUserContacts(userCode: String) {
        try {
            val rows = SupabaseApi.select("users", "user_id=eq.$userCode")
            if (rows.length() > 0) {
                val userObj = rows.getJSONObject(0)
                val contactCodesArr = userObj.optJSONArray("contacts") ?: JSONArray()
                val contactNamesObj = userObj.optJSONObject("contact_names") ?: JSONObject()

                val contactCodes = mutableListOf<String>()
                val contactNames = mutableMapOf<String, String>()

                for (i in 0 until contactCodesArr.length()) {
                    contactCodes.add(contactCodesArr.getString(i))
                }

                val keys = contactNamesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    contactNames[k] = contactNamesObj.getString(k)
                }

                val contacts = contactCodes.map { contactCode ->
                    Contact(
                        id = contactCode,
                        name = contactNames[contactCode] ?: "USER_${contactCode.take(4).uppercase()}",
                        status = ContactStatus.ONLINE,
                        lastActive = "RECENT"
                    )
                }

                _rawContacts.value = contacts

                // Always start session discovery regardless of mode so incoming alerts are universal
                startSessionDiscovery(contactCodes)
            }
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "Error fetching user contacts: ${e.message}")
        }
    }

    private fun observePairingRequests(userCode: String) {
        pairingRealtime?.stop()

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchPairingRequests(userCode)
                kotlinx.coroutines.delay(3000)
            }
        }

        pairingRealtime = SupabaseRealtimeClient("pairing_requests", "to_user_id", userCode) { type, record ->
            viewModelScope.launch(Dispatchers.IO) {
                fetchPairingRequests(userCode)
            }
        }.apply { start() }
    }

    private fun fetchPairingRequests(userCode: String) {
        try {
            val rows = SupabaseApi.select("pairing_requests", "to_user_id=eq.$userCode&status=eq.PENDING")
            val list = mutableListOf<PairingRequest>()
            val now = System.currentTimeMillis()
            val maxTtlMs = 10 * 60 * 1000L // 10 minutes

            for (i in 0 until rows.length()) {
                val obj = rows.getJSONObject(i)
                val req = PairingRequest(
                    requestId = obj.optString("request_id"),
                    fromUserId = obj.optString("from_user_id"),
                    fromUserName = obj.optString("from_user_name"),
                    toUserId = obj.optString("to_user_id"),
                    status = obj.optString("status"),
                    createdAt = obj.optLong("created_at")
                )

                if (now - req.createdAt > maxTtlMs) {
                    // Automatically update Supabase to EXPIRED in background
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val updateJson = JSONObject().apply {
                                put("status", SoSafeContract.Status.EXPIRED)
                            }
                            SupabaseApi.update("pairing_requests", "request_id=eq.${req.requestId}", updateJson)
                        } catch (e: Exception) {
                            Log.w("SOS_AUDIT", "Error updating expired request: ${e.message}")
                        }
                    }
                    cancelPairingNotification(req.requestId)

                    // If user tapped on this expired request via notification, surface expired notice
                    if (targetedPairingRequestId == req.requestId) {
                        _dashboardState.update { it.copy(expiredPairingNotice = req) }
                        targetedPairingRequestId = null
                    }
                } else {
                    list.add(req)
                }
            }
            _dashboardState.update { it.copy(pendingPairingRequests = list) }
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "Error fetching pairing requests: ${e.message}")
        }
    }

    private fun observeRemovalNotifications(userCode: String) {
        removalRealtime?.stop()

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchRemovalNotifications(userCode)
                kotlinx.coroutines.delay(3000)
            }
        }

        removalRealtime = SupabaseRealtimeClient("removal_notifications", "target_user_id", userCode) { type, record ->
            viewModelScope.launch(Dispatchers.IO) {
                fetchRemovalNotifications(userCode)
            }
        }.apply { start() }
    }

    private fun fetchRemovalNotifications(userCode: String) {
        try {
            val rows = SupabaseApi.select("removal_notifications", "target_user_id=eq.$userCode")
            val list = mutableListOf<RemovalNotification>()
            for (i in 0 until rows.length()) {
                val obj = rows.getJSONObject(i)
                list.add(
                    RemovalNotification(
                        notificationId = obj.optString("notification_id"),
                        removerId = obj.optString("remover_id"),
                        removerName = obj.optString("remover_name"),
                        targetUserId = obj.optString("target_user_id"),
                        createdAt = obj.optLong("created_at")
                    )
                )
            }
            _dashboardState.update { currentState: DashboardState -> currentState.copy(pendingRemovalNotifications = list) }
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "Error fetching removal notifications: ${e.message}")
        }
    }

    private fun observePairingNotifications(userCode: String) {
        pairingNotificationRealtime?.stop()

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchPairingNotifications(userCode)
                kotlinx.coroutines.delay(3000)
            }
        }

        pairingNotificationRealtime = SupabaseRealtimeClient("pairing_notifications", "target_user_id", userCode) { type, record ->
            viewModelScope.launch(Dispatchers.IO) {
                fetchPairingNotifications(userCode)
            }
        }.apply { start() }
    }

    private fun fetchPairingNotifications(userCode: String) {
        try {
            val rows = SupabaseApi.select("pairing_notifications", "target_user_id=eq.$userCode")
            if (rows.length() > 0) {
                for (i in 0 until rows.length()) {
                    val obj = rows.getJSONObject(i)
                    val notif = com.rohit.sosafe.data.contracts.PairingNotification(
                        notificationId = obj.optString("notification_id"),
                        targetUserId = obj.optString("target_user_id"),
                        fromUserId = obj.optString("from_user_id"),
                        fromUserName = obj.optString("from_user_name"),
                        type = obj.optString("type"),
                        createdAt = obj.optLong("created_at")
                    )

                    // 1. Immediately refresh contacts live
                    fetchUserContacts(userCode)

                    // 2. Set newly linked notice for UI banner/dialog
                    _dashboardState.update { it.copy(newlyLinkedNotice = notif) }

                    // 3. Delete notification from Supabase so it only triggers once
                    viewModelScope.launch(Dispatchers.IO) {
                        userManager.dismissPairingNotification(notif.notificationId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "Error fetching pairing notifications: ${e.message}")
        }
    }

    fun clearNewlyLinkedNotice() {
        _dashboardState.update { it.copy(newlyLinkedNotice = null) }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            combine(
                ServiceState.isGuardianActive,
                ServiceState.isEmergencyActive
            ) { isGuardian, isEmergency ->
                Pair(isGuardian, isEmergency)
            }.collect { (isGuardian, isEmergency) ->
                _dashboardState.update { currentState: DashboardState -> currentState.copy(
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
            sessionsRealtime?.stop()
            return
        }

        sessionsRealtime?.stop()

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                fetchActiveSessions(contactIds)
                kotlinx.coroutines.delay(2000)
            }
        }

        sessionsRealtime = SupabaseRealtimeClient("sessions") { type, record ->
            viewModelScope.launch(Dispatchers.IO) {
                fetchActiveSessions(contactIds)
            }
        }.apply { start() }
    }

    private fun fetchActiveSessions(contactIds: List<String>) {
        try {
            val myCode = userManager.getUserCodeSync() ?: ""
            val rows = SupabaseApi.select("sessions", "status=eq.ACTIVE")
            val list = mutableListOf<SosSession>()
            for (i in 0 until rows.length()) {
                val obj = rows.getJSONObject(i)
                val senderId = obj.optString("sender_id")
                if (senderId != myCode && senderId in contactIds) {
                    val lat = obj.optDouble("last_latitude", Double.NaN)
                    val lng = obj.optDouble("last_longitude", Double.NaN)
                    val geoPoint = if (!lat.isNaN() && !lng.isNaN()) {
                        com.google.firebase.firestore.GeoPoint(lat, lng)
                    } else null

                    list.add(
                        SosSession(
                            sessionId = obj.optString("session_id"),
                            senderId = senderId,
                            guardianId = obj.optString("guardian_id"),
                            status = obj.optString("status"),
                            startedAt = obj.optLong("started_at"),
                            lastLocation = geoPoint,
                            lastUpdatedAt = obj.optLong("last_updated_at"),
                            streamingMode = obj.optString("streaming_mode", "HYBRID"),
                            webrtcOffer = obj.optString("webrtc_offer"),
                            webrtcAnswer = obj.optString("webrtc_answer")
                        )
                    )
                }
            }
            _activeSessions.value = list
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "Error fetching active sessions: ${e.message}")
        }
    }

    fun validateUserCode(code: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = userManager.validateUserCode(code)
            onResult(result)
        }
    }

    fun sendPairingRequest(code: String, customName: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = userManager.sendPairingRequest(code, customName)
            onResult(result)
        }
    }

    fun pairViaQrCode(scannedUserId: String, customName: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = userManager.pairViaQrCode(scannedUserId, customName)
            if (res.isSuccess) {
                val code = userManager.getUserCode()
                fetchUserContacts(code)
            }
            withContext(Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun acceptPairingRequest(request: PairingRequest, customName: String = "") {
        cancelPairingNotification(request.requestId)
        viewModelScope.launch(Dispatchers.IO) {
            val res = userManager.acceptPairingRequest(request.requestId, request.fromUserId, customName)
            if (res.isSuccess) {
                val code = userManager.getUserCode()
                fetchUserContacts(code)
                fetchPairingRequests(code)
            }
        }
    }

    fun declinePairingRequest(request: PairingRequest) {
        cancelPairingNotification(request.requestId)
        viewModelScope.launch(Dispatchers.IO) {
            val res = userManager.declinePairingRequest(request.requestId)
            if (res.isSuccess) {
                val code = userManager.getUserCode()
                fetchPairingRequests(code)
            }
        }
    }

    fun onPairingNotificationOpened(requestId: String, fromUserId: String, fromUserName: String, createdAt: Long) {
        val now = System.currentTimeMillis()
        val maxTtlMs = 10 * 60 * 1000L
        cancelPairingNotification(requestId)

        if (now - createdAt > maxTtlMs) {
            val expiredReq = PairingRequest(
                requestId = requestId,
                fromUserId = fromUserId,
                fromUserName = fromUserName,
                createdAt = createdAt,
                status = SoSafeContract.Status.EXPIRED
            )
            _dashboardState.update { it.copy(expiredPairingNotice = expiredReq) }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val updateJson = JSONObject().apply {
                        put("status", SoSafeContract.Status.EXPIRED)
                    }
                    SupabaseApi.update("pairing_requests", "request_id=eq.$requestId", updateJson)
                } catch (e: Exception) {
                    Log.w("SOS_AUDIT", "Error marking request as expired: ${e.message}")
                }
            }
        } else {
            targetedPairingRequestId = requestId
            viewModelScope.launch(Dispatchers.IO) {
                val code = userManager.getUserCode()
                fetchPairingRequests(code)
            }
        }
    }

    fun clearExpiredNotice() {
        _dashboardState.update { it.copy(expiredPairingNotice = null) }
    }

    private fun cancelPairingNotification(requestId: String) {
        try {
            val nm = userManager.context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(requestId.hashCode())
        } catch (e: Exception) {
            Log.w("SOS_AUDIT", "Error cancelling notification: ${e.message}")
        }
    }

    fun renameContact(contactId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = userManager.updateContactName(contactId, newName)
            if (res.isSuccess) {
                val code = userManager.getUserCode()
                fetchUserContacts(code)
            }
        }
    }

    fun removeContact(targetCode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = userManager.removeContact(targetCode)
            if (res.isSuccess) {
                val code = userManager.getUserCode()
                fetchUserContacts(code)
            }
        }
    }

    fun dismissRemovalNotification(notification: RemovalNotification) {
        viewModelScope.launch {
            userManager.dismissRemovalNotification(notification.notificationId)
            _dashboardState.update { currentState: DashboardState ->
                currentState.copy(pendingRemovalNotifications = currentState.pendingRemovalNotifications.filter { it.notificationId != notification.notificationId })
            }
        }
    }

    fun dismissSession(sessionId: String) {
        _dashboardState.update { currentState: DashboardState ->
            currentState.copy(
                dismissedSessions = currentState.dismissedSessions + sessionId
            )
        }
    }

    fun loadRecordingsForUser(userId: String) {
        viewModelScope.launch {
            val recordings = recordingManager.getRecordingsForUser(userId)
            _dashboardState.update { currentState: DashboardState -> currentState.copy(selectedUserRecordings = recordings) }
        }
    }

    fun deleteRecording(userId: String, sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete locally
            recordingManager.deleteRecording(userId, sessionId)
            
            // Clear from Cloud (Supabase)
            try {
                SupabaseApi.delete("sessions", "session_id=eq.$sessionId")
                SupabaseApi.delete("audio_chunks", "session_id=eq.$sessionId")
                Log.d("SOS_AUDIT", "CLOUD_RECORDING_DELETED: $sessionId")
            } catch (e: Exception) {
                Log.e("SOS_AUDIT", "CLOUD_RECORDING_DELETE_ERROR: ${e.message}")
            }

            // Refresh recordings list
            val updatedRecordings = recordingManager.getRecordingsForUser(userId)
            _dashboardState.update { currentState: DashboardState -> currentState.copy(selectedUserRecordings = updatedRecordings) }
        }
    }

    fun selectPlaybackRecording(recording: RecordingInfo?) {
        _dashboardState.update { currentState: DashboardState -> currentState.copy(selectedPlaybackRecording = recording) }
    }

    override fun onCleared() {
        super.onCleared()
        userRealtime?.stop()
        pairingRealtime?.stop()
        removalRealtime?.stop()
        sessionsRealtime?.stop()
    }
}
