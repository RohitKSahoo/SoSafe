package com.rohit.sosafe.utils

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.rohit.sosafe.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SirenPlayer {
    private const val TAG = "SOS_SIREN_PLAYER"

    private val _isSirenPlaying = MutableStateFlow(false)
    val isSirenPlaying: StateFlow<Boolean> = _isSirenPlaying.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun start(context: Context) {
        if (_isSirenPlaying.value) {
            Log.d(TAG, "SIREN_ALREADY_PLAYING: Ignoring duplicate start request.")
            return
        }

        Log.d(TAG, "SIREN_START: Initializing emergency audio and vibration alert...")

        // Acquire WakeLock so CPU doesn't suspend during locked screen playback
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoSafe:SirenWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes */)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire siren wakeLock: ${e.message}")
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Attempt to maximize STREAM_ALARM volume to ensure SOS is heard even if volume was low or phone was on silent
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val canChangePolicy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.isNotificationPolicyAccessGranted
            } else {
                true
            }

            if (canChangePolicy) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                Log.d(TAG, "STREAM_ALARM set to max volume $maxVolume via policy access.")
            } else {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                if (currentVolume < maxVolume / 2) {
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                    } catch (se: SecurityException) {
                        Log.w(TAG, "DND restriction prevented setting alarm volume: ${se.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Volume adjustment skipped: ${e.message}")
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        // Start Hardware Vibration loop
        startVibration(context, audioAttributes)

        // Start looping MediaPlayer siren on STREAM_ALARM
        try {
            val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.siren}")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context.applicationContext, soundUri)
                setAudioAttributes(audioAttributes)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            _isSirenPlaying.value = true
            Log.d(TAG, "SIREN_STARTED: Audio stream alarm active with audibility enforced.")
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed to start: ${e.message}, falling back to system alarm ringtone.")
            playFallbackAlarm(context, audioAttributes)
        }
    }

    private fun playFallbackAlarm(context: Context, audioAttributes: AudioAttributes) {
        try {
            var alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            fallbackRingtone = RingtoneManager.getRingtone(context.applicationContext, alertUri)?.apply {
                this.audioAttributes = audioAttributes
                play()
            }
            _isSirenPlaying.value = true
            Log.d(TAG, "FALLBACK_ALARM_STARTED: System alarm ringtone playing.")
        } catch (e: Exception) {
            _isSirenPlaying.value = false
            Log.e(TAG, "FALLBACK_ALARM_FAILED: ${e.message}")
        }
    }

    private fun startVibration(context: Context, audioAttributes: AudioAttributes) {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0) // repeat indefinitely from index 0
                vibrator?.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d(TAG, "VIBRATION_STARTED: Continuous emergency pulse active.")
        } catch (e: Exception) {
            Log.w(TAG, "Vibration start failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop(context: Context) {
        Log.d(TAG, "SIREN_STOP: Stopping audio alert and vibration.")
        try {
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

            vibrator?.cancel()
            vibrator = null

            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null

            _isSirenPlaying.value = false
            Log.d(TAG, "SIREN_STOPPED: Resources cleaned up successfully.")
        } catch (e: Exception) {
            _isSirenPlaying.value = false
            Log.e(TAG, "Error stopping siren: ${e.message}")
        }
    }
}
