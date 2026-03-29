package com.rohit.sosafe.data

import android.content.Context
import android.content.SharedPreferences

class StreamingModeManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("streaming_prefs", Context.MODE_PRIVATE)

    fun getStreamingMode(): StreamingMode {
        val modeName = prefs.getString("streaming_mode", StreamingMode.HYBRID.name)
        return try {
            StreamingMode.valueOf(modeName ?: StreamingMode.HYBRID.name)
        } catch (e: Exception) {
            StreamingMode.HYBRID
        }
    }

    fun setStreamingMode(mode: StreamingMode) {
        prefs.edit().putString("streaming_mode", mode.name).apply()
    }
}
