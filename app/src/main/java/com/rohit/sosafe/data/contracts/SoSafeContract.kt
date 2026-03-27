package com.rohit.sosafe.data.contracts

import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.FieldValue

/**
 * Shared Contract System for SoSafe.
 * Based on SoSafe System Architecture v3.
 */
object SoSafeContract {

    object Collections {
        const val USERS = "users"
        const val SESSIONS = "sessions"
        const val AUDIO_CHUNKS = "audio_chunks"
    }

    object Fields {
        // User document fields
        const val USER_ID = "userId"
        const val CONTACTS = "contacts"
        const val CONTACT_NAMES = "contactNames" // New field for mapping ID -> Custom Name
        const val FCM_TOKEN = "fcmToken"
        const val CREATED_AT = "createdAt"
        
        // Session fields
        const val SESSION_ID = "sessionId"
        const val SENDER_ID = "senderId"
        const val GUARDIAN_ID = "guardianId"
        const val STATUS = "status"
        const val STARTED_AT = "startedAt"
        const val LAST_LOCATION = "lastLocation"
        const val LAST_UPDATED_AT = "lastUpdatedAt"
        
        // Audio Chunk fields
        const val FILE_URL = "fileUrl"
        const val SEQUENCE = "sequence"
        const val DURATION = "duration"
    }

    object Status {
        const val ACTIVE = "ACTIVE"
        const val ENDED = "ENDED"
    }

    // --- Dynamic Path Helpers ---
    
    fun getUsersCollection() = Collections.USERS
    
    fun getUserDocumentPath(userId: String) = "${Collections.USERS}/$userId"
    
    fun getSessionsCollection() = Collections.SESSIONS
    
    fun getSessionDocumentPath(sessionId: String) = "${Collections.SESSIONS}/$sessionId"
    
    fun getAudioChunksSubcollection(sessionId: String) = 
        "${getSessionDocumentPath(sessionId)}/${Collections.AUDIO_CHUNKS}"
}

// --- Data Models ---

data class User(
    val userId: String = "",
    val contacts: List<String> = emptyList(),
    val contactNames: Map<String, String> = emptyMap(), // Mapping ID -> Custom Name
    val fcmToken: String = "",
    val createdAt: Long = 0L
)

data class SosSession(
    val sessionId: String = "",
    val senderId: String = "",
    val guardianId: String = "",
    val status: String = SoSafeContract.Status.ACTIVE,
    val startedAt: Any? = null,
    val lastLocation: GeoPoint? = null,
    val lastUpdatedAt: Any? = null
)

data class AudioChunk(
    val fileUrl: String = "",
    val sequence: Int = 0,
    val duration: Int = 0,
    val createdAt: Any? = null
)
