package com.rohit.sosafe.ui

import com.rohit.sosafe.data.StreamingMode
import com.rohit.sosafe.data.contracts.PairingRequest
import com.rohit.sosafe.data.contracts.RemovalNotification
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.utils.RecordingInfo

enum class ContactStatus {
    ONLINE,
    OFFLINE,
    EMERGENCY
}

data class Contact(
    val id: String,
    val name: String,
    val status: ContactStatus = ContactStatus.ONLINE,
    val lastActive: String = "RECENT",
    val activeSession: SosSession? = null
)

data class DashboardState(
    val userCode: String = "--------",
    val isProtectionActive: Boolean = false,
    val isEmergency: Boolean = false,
    val connectionStatus: String = "CONNECTED",
    val isNetworkConnected: Boolean = true,
    val networkQuality: String = "EXCELLENT (VOICE OK)", // EXCELLENT (VOICE OK), POOR (AUDIO DELAY), NO INTERNET
    val networkType: String = "WIFI", // WIFI, 4G, 5G, 3G, OFFLINE
    val broadcastStatus: String = "IDLE",
    val contacts: List<Contact> = emptyList(),
    val activeEmergencySession: SosSession? = null,
    val pendingPairingRequests: List<PairingRequest> = emptyList(),
    val pendingRemovalNotifications: List<RemovalNotification> = emptyList(),
    val dismissedSessions: List<String> = emptyList(),
    val selectedUserRecordings: List<RecordingInfo> = emptyList(),
    val selectedPlaybackRecording: RecordingInfo? = null,
    val streamingMode: StreamingMode = StreamingMode.HYBRID
)
