package com.rohit.sosafe.data.contracts

import com.google.firebase.firestore.GeoPoint

/**
 * Shared Contract System for SoSafe Sender and Receiver Apps.
 * This file serves as the single source of truth for Firestore paths, field names, and data models.
 */
object SoSafeContract {

    object Collections {
        const val USERS = "users"
        const val ALERTS = "alerts"
        const val SOS_SESSIONS = "sos_sessions"
        
        // Subcollections
        const val LOCATION_UPDATES = "location_updates"
        const val AUDIO_CHUNKS = "audio_chunks"
    }

    object Fields {
        // User document fields
        const val USER_CODE = "userCode"
        const val CONTACTS = "contacts"
        const val CREATED_AT = "createdAt"
        
        // Alert document fields
        const val SENDER_ID = "senderId"
        const val RECEIVER_ID = "receiverId"
        const val SESSION_ID = "sessionId"
        const val STATUS = "status"
        
        // Location Update fields
        const val LOCATION = "location"
        const val TIMESTAMP = "timestamp"
        
        // Audio Chunk fields
        const val FILE_URL = "fileUrl"
        
        // Session fields
        const val STARTED_AT = "startedAt"
    }

    object Status {
        const val SENT = "sent"
        const val ACTIVE = "active"
    }

    // --- Dynamic Path Helpers ---
    
    fun getUsersCollection() = Collections.USERS
    
    fun getUserDocumentPath(userCode: String) = "${Collections.USERS}/$userCode"
    
    fun getAlertsCollection() = Collections.ALERTS
    
    fun getSessionsCollection() = Collections.SOS_SESSIONS
    
    fun getSessionDocumentPath(sessionId: String) = "${Collections.SOS_SESSIONS}/$sessionId"
    
    fun getLocationUpdatesSubcollection(sessionId: String) = 
        "${getSessionDocumentPath(sessionId)}/${Collections.LOCATION_UPDATES}"
    
    fun getAudioChunksSubcollection(sessionId: String) = 
        "${getSessionDocumentPath(sessionId)}/${Collections.AUDIO_CHUNKS}"
}

// --- Data Models ---

data class User(
    val userCode: String = "",
    val contacts: List<String> = emptyList(),
    val createdAt: Long = 0L
)

data class Alert(
    val senderId: String = "",
    val receiverId: String = "",
    val sessionId: String = "",
    val status: String = SoSafeContract.Status.SENT,
    val createdAt: Long = 0L
)

data class LocationUpdate(
    val location: GeoPoint? = null,
    val timestamp: Long = 0L
)

data class AudioChunk(
    val fileUrl: String = "",
    val timestamp: Long = 0L
)

data class SosSession(
    val sessionId: String = "",
    val senderId: String = "",
    val startedAt: Long = 0L
)
