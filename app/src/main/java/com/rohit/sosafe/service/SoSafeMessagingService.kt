package com.rohit.sosafe.service

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SoSafeMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("SoSafeFCM", "From: ${remoteMessage.from}")

        if (remoteMessage.data.isNotEmpty()) {
            val type = remoteMessage.data["type"]
            if (type == "SOS_TRIGGER") {
                val sessionId = remoteMessage.data["sessionId"]
                val senderId = remoteMessage.data["senderId"]
                val senderName = remoteMessage.data["senderName"]

                val serviceIntent = Intent(this, SOSForegroundService::class.java).apply {
                    action = SOSForegroundService.ACTION_GUARDIAN_SOS
                    putExtra("sessionId", sessionId)
                    putExtra("senderId", senderId)
                    putExtra("senderName", senderName)
                }
                
                startForegroundService(serviceIntent)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("SoSafeFCM", "New token: $token")
        // In a real app, we would send this to the backend/Firestore
    }
}
