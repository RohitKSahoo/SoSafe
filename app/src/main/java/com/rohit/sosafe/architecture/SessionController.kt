package com.rohit.sosafe.architecture

import android.util.Log
import com.rohit.sosafe.data.contracts.SoSafeContract
import com.rohit.sosafe.data.supabase.SupabaseApi
import com.rohit.sosafe.data.supabase.SupabaseRealtimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for SOS Session Lifecycle (Supabase Edition).
 */
class SessionController(
    private val sessionId: String
) {
    private val TAG = "SessionController"
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var realtimeClient: SupabaseRealtimeClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startMonitoring() {
        if (_sessionState.value !is SessionState.IDLE) return
        
        _sessionState.value = SessionState.CONNECTING
        Log.d(TAG, "Monitoring started for: $sessionId")

        // Initial fetch
        scope.launch {
            try {
                val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                if (rows.length() > 0) {
                    val status = rows.getJSONObject(0).optString("status")
                    handleRemoteUpdate(status)
                } else {
                    _sessionState.value = SessionState.ENDED
                }
            } catch (e: Exception) {
                _sessionState.value = SessionState.ERROR(e.message ?: "Unknown error")
            }
        }

        // Realtime subscription
        realtimeClient = SupabaseRealtimeClient("sessions", "session_id", sessionId) { type, record ->
            val status = record.optString("status")
            handleRemoteUpdate(status)
        }.apply { start() }
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
                    _sessionState.value = SessionState.ENDED
                }
            }
        }
    }

    fun stopMonitoring() {
        realtimeClient?.stop()
        realtimeClient = null
        _sessionState.value = SessionState.ENDED
        Log.d(TAG, "Monitoring stopped manually")
    }
}
