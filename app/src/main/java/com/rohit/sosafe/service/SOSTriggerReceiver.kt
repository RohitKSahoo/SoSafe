package com.rohit.sosafe.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SOSTriggerReceiver(private val onTrigger: () -> Unit) : BroadcastReceiver() {
    private var pressCount = 0
    private var lastPressTime: Long = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
            val currentTime = System.currentTimeMillis()
            // If toggles are within 1.5 seconds of each other
            if (currentTime - lastPressTime < 1500) {
                pressCount++
            } else {
                pressCount = 1
            }
            lastPressTime = currentTime

            Log.d("SOSTriggerReceiver", "Screen toggle detected. Count: $pressCount")

            if (pressCount >= 3) { // Updated to 3 toggles (presses) to trigger SOS
                Log.d("SOSTriggerReceiver", "Power button SOS triggered (3 presses)")
                onTrigger()
                pressCount = 0
            }
        }
    }
}
