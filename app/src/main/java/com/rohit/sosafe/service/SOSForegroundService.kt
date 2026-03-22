package com.rohit.sosafe.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.rohit.sosafe.MainActivity
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.data.UserManager
import java.io.File

class SOSForegroundService : Service() {

    private val CHANNEL_ID = "SOS_SERVICE_CHANNEL"
    private val NOTIFICATION_ID = 1
    private var sosTriggerManager: SOSTriggerManager? = null
    private var isEmergencyActive = false
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var userManager: UserManager
    private val db = Firebase.firestore
    private val storage = Firebase.storage

    // Audio recording variables
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private val audioHandler = Handler(Looper.getMainLooper())
    private val CHUNK_DURATION_MS = 5000L // 5 second chunks

    companion object {
        const val ACTION_START_EMERGENCY = "ACTION_START_EMERGENCY"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        userManager = UserManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sosTriggerManager = SOSTriggerManager(this)
        sosTriggerManager?.startDetection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_EMERGENCY) {
            startEmergencyMode()
        } else {
            startGuardianMode()
        }
        return START_STICKY
    }

    private fun startGuardianMode() {
        val notification = createNotification(
            "SoSafe Protection Active", 
            "Shake phone or press power button 3x to trigger SOS."
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startEmergencyMode() {
        if (isEmergencyActive) return
        isEmergencyActive = true
        
        val notification = createNotification(
            "!!! EMERGENCY SOS ACTIVE !!!", 
            "Broadcasting location and recording audio."
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        
        Log.d("SOSForegroundService", "Emergency Mode Activated!")
        
        startLocationStreaming()
        startAudioChunking()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationStreaming() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(3000)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    uploadLocation(location.latitude, location.longitude)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun uploadLocation(lat: Double, lng: Double) {
        val userCode = userManager.getUserCodeSync() ?: "unknown"
        val locationData = hashMapOf(
            "lat" to lat,
            "lng" to lng,
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("sos_sessions")
            .document(userCode)
            .collection("location_updates")
            .add(locationData)
    }

    private fun startAudioChunking() {
        recordNextChunk()
    }

    private fun recordNextChunk() {
        if (!isEmergencyActive) return

        try {
            currentAudioFile = File(cacheDir, "chunk_${System.currentTimeMillis()}.aac")
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentAudioFile?.absolutePath)
                prepare()
                start()
            }

            Log.d("SOSForegroundService", "Started recording audio chunk")

            // Schedule stop and upload after duration
            audioHandler.postDelayed({
                stopAndUploadChunk()
            }, CHUNK_DURATION_MS)

        } catch (e: Exception) {
            Log.e("SOSForegroundService", "Audio recording failed", e)
        }
    }

    private fun stopAndUploadChunk() {
        val fileToUpload = currentAudioFile
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            Log.d("SOSForegroundService", "Stopped recording chunk")

            if (fileToUpload != null && fileToUpload.exists()) {
                uploadAudioFile(fileToUpload)
            }

            // Start next chunk immediately
            recordNextChunk()

        } catch (e: Exception) {
            Log.e("SOSForegroundService", "Error stopping recorder", e)
            recordNextChunk() // Attempt to restart regardless
        }
    }

    private fun uploadAudioFile(file: File) {
        val userCode = userManager.getUserCodeSync() ?: "unknown"
        val fileName = file.name
        val storageRef = storage.reference.child("audio_chunks/$userCode/$fileName")

        storageRef.putFile(Uri.fromFile(file))
            .addOnSuccessListener {
                Log.d("SOSForegroundService", "Audio chunk uploaded: $fileName")
                file.delete() // Delete local file after upload
            }
            .addOnFailureListener { e ->
                Log.e("SOSForegroundService", "Audio upload failed", e)
            }
    }

    override fun onDestroy() {
        isEmergencyActive = false
        audioHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_report_image)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "SoSafe Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}