package com.rohit.sosafe.utils

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {
    private var lastUpdate: Long = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private val SHAKE_THRESHOLD = 800
    private var shakeCount = 0
    private var lastShakeTimestamp: Long = 0

    override fun onSensorChanged(event: SensorEvent) {
        val curTime = System.currentTimeMillis()
        if ((curTime - lastUpdate) > 100) {
            val diffTime = curTime - lastUpdate
            lastUpdate = curTime

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val speed = sqrt(((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ)).toDouble()) / diffTime * 10000

            if (speed > SHAKE_THRESHOLD) {
                if (curTime - lastShakeTimestamp < 500) {
                    shakeCount++
                    if (shakeCount > 15) { // Threshold for sustained shake
                        onShake()
                        shakeCount = 0
                    }
                } else {
                    shakeCount = 0
                }
                lastShakeTimestamp = curTime
            }

            lastX = x
            lastY = y
            lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}