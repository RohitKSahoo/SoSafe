package com.rohit.sosafe.architecture

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.rohit.sosafe.data.contracts.SoSafeContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for SOS Session Lifecycle.
 */
class SessionController(
    private val db: FirebaseFirestore,
    private val sessionId: String
) {
    private val TAG = "SessionController"
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var sessionListener: ListenerRegistration? = null

    fun startMonitoring() {
        if (_sessionState.value !is SessionState.IDLE) return
        
        _sessionState.value = SessionState.CONNECTING
        Log.d(TAG, "Monitoring started for: $sessionId")

        sessionListener = db.collection(SoSafeContract.Collections.SESSIONS)
            .document(sessionId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    _sessionState.value = SessionState.ERROR(e.message ?: "Unknown error")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString(SoSafeContract.Fields.STATUS)
                    handleRemoteUpdate(status)
                } else {
                    _sessionState.value = SessionState.ENDED
                }
            }
    }

    private fun handleRemoteUpdate(status: String?) {
        when (status) {
            SoSafeContract.Status.ACTIVE -> {
                if (_sessionState.value !is SessionState.ACTIVE) {
                    _sessionState.value = SessionState.ACTIVE(sessionId)
                    Log.d(TAG, "Session marked ACTIVE")
                }
            }
            SoSafeContract.Status.ENDED -> {
                if (_sessionState.value is SessionState.ACTIVE || _sessionState.value is SessionState.CONNECTING) {
                    _sessionState.value = SessionState.TERMINATING
                    Log.d(TAG, "Session marked TERMINATING")
                    // Perform any internal cleanup if needed, then move to ENDED
                    _sessionState.value = SessionState.ENDED
                }
            }
        }
    }

    fun stopMonitoring() {
        sessionListener?.remove()
        sessionListener = null
        _sessionState.value = SessionState.ENDED
        Log.d(TAG, "Monitoring stopped manually")
    }
}
