package com.rohit.sosafe.architecture

/**
 * Deterministic states for an SOS Session.
 * Based on SOSafe Architecture v4.
 */
sealed class SessionState {
    object IDLE : SessionState()
    object CONNECTING : SessionState()
    data class ACTIVE(val sessionId: String) : SessionState()
    object TERMINATING : SessionState()
    object ENDED : SessionState()
    data class ERROR(val message: String) : SessionState()
}
