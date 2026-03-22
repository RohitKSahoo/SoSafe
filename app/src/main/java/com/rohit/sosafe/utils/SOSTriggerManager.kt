package com.rohit.sosafe.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rohit.sosafe.service.SOSTriggerReceiver
import com.rohit.sosafe.service.SOSForegroundService

class SOSTriggerManager(private val context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val shakeDetector = ShakeDetector { initiateSOS() }
    private val powerButtonReceiver = SOSTriggerReceiver { initiateSOS() }
    private var isDetectionRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var sosPendingRunnable: Runnable? = null

    fun startDetection() {
        if (isDetectionRunning) return
        
        // Register Shake Detection
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI)

        // Register Power Button Detection
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(powerButtonReceiver, filter)
        
        isDetectionRunning = true
        Log.d("SOSTriggerManager", "Detection Started")
    }

    fun manualTrigger() {
        initiateSOS()
    }

    private fun initiateSOS() {
        if (sosPendingRunnable != null) return // Already triggering

        Log.d("SOSTriggerManager", "SOS TRIGGERED - Entering Cancel Window (2s)")
        vibrateFeedback()
        
        sosPendingRunnable = Runnable {
            Log.d("SOSTriggerManager", "Cancel window expired. Activating Emergency Mode.")
            startEmergencySOS()
            sosPendingRunnable = null
        }
        
        handler.postDelayed(sosPendingRunnable!!, 2000)
    }

    private fun startEmergencySOS() {
        val serviceIntent = Intent(context, SOSForegroundService::class.java).apply {
            action = SOSForegroundService.ACTION_START_EMERGENCY
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    private fun vibrateFeedback() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(2000)
        }
    }
}