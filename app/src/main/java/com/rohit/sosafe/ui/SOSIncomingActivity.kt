package com.rohit.sosafe.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.ui.theme.SoSafeTheme

class SOSIncomingActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId: String = ""
    private var isSirenPlaying by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = intent.getStringExtra("sessionId") ?: ""
        val senderId = intent.getStringExtra("senderId") ?: ""
        val senderName = intent.getStringExtra("senderName") ?: "Someone"

        // Cancel system notification banner so custom Activity takes control
        cancelNotification()

        showOnLockScreen()
        startSiren()

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
                        isSirenActive = isSirenPlaying,
                        onSilenceSiren = { stopSiren() },
                        onClose = { 
                            Log.d("SOS_AUDIT", "MONITORING_CLOSED: Closing activity.")
                            stopSiren()
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
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Safely attempt to maximize alarm volume without crashing in DND mode
        try {
            val canChangePolicy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.isNotificationPolicyAccessGranted
            } else {
                true
            }

            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (canChangePolicy) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                Log.d("SOS_AUDIT", "ALARM_VOLUME_SET: Max volume applied via policy access.")
            } else {
                // If policy access is not granted, check if alarm is muted or low
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                if (currentVolume < maxVolume / 2) {
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                    } catch (se: SecurityException) {
                        Log.w("SOS_AUDIT", "DND policy restriction prevented setStreamVolume: ${se.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SOS_AUDIT", "Could not adjust alarm stream volume: ${e.message}")
        }

        // Start Hardware Vibration in parallel
        startVibration()

        // Initialize and start MediaPlayer for Siren
        try {
            val soundUri = Uri.parse("android.resource://$packageName/raw/siren")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            isSirenPlaying = true
            Log.d("SOS_AUDIT", "SIREN_STARTED: Audio stream alarm active with audibility enforced.")
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "SIREN_START_FAILED: ${e.message}, falling back to system alarm ringtone.")
            playFallbackAlarm()
        }
    }

    private fun playFallbackAlarm() {
        try {
            var alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            fallbackRingtone = RingtoneManager.getRingtone(applicationContext, alertUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
                play()
            }
            isSirenPlaying = true
            Log.d("SOS_AUDIT", "FALLBACK_ALARM_STARTED: Ringtone playing.")
        } catch (e: Exception) {
            isSirenPlaying = false
            Log.e("SOS_AUDIT", "FALLBACK_ALARM_FAILED: ${e.message}")
        }
    }

    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0) // repeat indefinitely
                vibrator?.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w("SOS_AUDIT", "Vibration start failed: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            Log.w("SOS_AUDIT", "Vibration stop failed: ${e.message}")
        }
    }

    private fun stopSiren() {
        try {
            cancelNotification()
            
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null

            fallbackRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            fallbackRingtone = null

            stopVibration()

            isSirenPlaying = false
            Log.d("SOS_AUDIT", "SIREN_STOPPED: All audio and vibration resources released.")
        } catch (e: Exception) {
            isSirenPlaying = false
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
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
