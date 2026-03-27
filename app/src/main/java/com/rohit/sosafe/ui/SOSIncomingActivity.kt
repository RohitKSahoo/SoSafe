package com.rohit.sosafe.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.ui.theme.SoSafeTheme

class SOSIncomingActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = intent.getStringExtra("sessionId") ?: ""
        val senderId = intent.getStringExtra("senderId") ?: ""
        val senderName = intent.getStringExtra("senderName") ?: "Someone"

        // BUG FIX: Cancel the notification immediately to stop the "System" siren
        cancelNotification()

        showOnLockScreen()
        startSiren()

        // Auto-stop siren after 3 seconds
        handler.postDelayed({
            Log.d("SOS_AUDIT", "SIREN_TIMEOUT: Stopping siren manually.")
            stopSiren()
        }, 3000)

        val session = SosSession(
            sessionId = sessionId,
            senderId = senderId,
            status = "ACTIVE"
        )

        setContent {
            SoSafeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MonitoringScreen(
                        session = session,
                        displayName = senderName, // Pass custom name from notification
                        initialDelayMillis = 3000L,
                        onClose = { 
                            Log.d("SOS_AUDIT", "MONITORING_CLOSED: Closing activity.")
                            finish() 
                        }
                    )
                }
            }
        }
    }

    private fun cancelNotification() {
        if (sessionId.isNotEmpty()) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(sessionId.hashCode())
            Log.d("SOS_AUDIT", "NOTIFICATION_CANCELLED: Activity took control.")
        }
    }

    private fun startSiren() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Force Alarm Volume to Max
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        try {
            mediaPlayer = MediaPlayer().apply {
                val soundUri = Uri.parse("android.resource://$packageName/raw/siren")
                setDataSource(applicationContext, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            Log.d("SOS_AUDIT", "SIREN_STARTED: Audio stream alarm active.")
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "SIREN_START_FAILED: ${e.message}")
        }
    }

    private fun stopSiren() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
            Log.d("SOS_AUDIT", "SIREN_STOPPED: Resource released.")
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "SIREN_STOP_FAILED: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        stopSiren()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSiren()
        super.onDestroy()
    }

    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}
