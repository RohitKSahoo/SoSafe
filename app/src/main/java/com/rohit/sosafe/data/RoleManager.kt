package com.rohit.sosafe.data

import android.util.Log

object RoleManager {
    var role: String = "" // "SENDER" or "GUARDIAN"
    var myUserId: String = ""
    var pairedUserId: String = ""

    fun isSender() = role == "SENDER"
    fun isGuardian() = role == "GUARDIAN"

    fun updateAuditLog() {
        Log.d("SOS_AUDIT", "ROLE=$role, MY_ID=$myUserId, TARGET=$pairedUserId")
    }
}