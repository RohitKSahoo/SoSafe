package com.rohit.sosafe.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("SoSafe_Boot", "Boot completed. Starting SOS Service...")
            val serviceIntent = Intent(context, SOSForegroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
