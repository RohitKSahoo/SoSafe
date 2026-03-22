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
import com.rohit.sosafe.MainActivity
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.utils.CloudinaryUploader
import com.rohit.sosafe.utils.ServiceState
import java.io.File
import java.util.UUID

class SOSForegroundService : Service() {

    private val CHANNEL_ID = "SOS_SERVICE_CHANNEL"
    private val NOTIFICATION_ID = 1
    private var sosTriggerManager: SOSTriggerManager? = null
    private var isEmergencyActive = false
    private var sessionId: String = ""
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var userManager: UserManager
    private val db = Firebase.firestore
    
    private val cloudinaryUploader = CloudinaryUploader()

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private val audioHandler = Handler(Looper.getMainLooper())
    private val CHUNK_DURATION_MS = 5000L

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
        ServiceState.setGuardianActive(true)
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
        val notification = createNotification("SoSafe Protection Active", "Monitoring for SOS triggers...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startEmergencyMode() {
        if (isEmergencyActive) return
        isEmergencyActive = true
        sessionId = UUID.randomUUID().toString()
        ServiceState.setEmergencyActive(true)
        
        val notification = createNotification("!!! EMERGENCY SOS ACTIVE !!!", "Broadcasting alerts, location and audio.")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        
        triggerAlerts()
        startLocationStreaming()
        startAudioChunking()
    }

    private fun triggerAlerts() {
        val myCode = userManager.getUserCodeSync() ?: return
        
        // Fetch contacts and create alert for each
        db.collection("users").document(myCode).collection("contacts").get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val contactId = doc.getString("contactCode") ?: continue
                    val alertData = hashMapOf(
                        "senderId" to myCode,
                        "sessionId" to sessionId,
                        "timestamp" to System.currentTimeMillis(),
                        "status" to "active"
                    )
                    // Write to alerts/{contactId}
                    db.collection("alerts").document(contactId).set(alertData)
                        .addOnSuccessListener { Log.d("AlertSystem", "Alert sent to $contactId") }
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationStreaming() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.locations.forEach { uploadLocation(it.latitude, it.longitude) }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun uploadLocation(lat: Double, lng: Double) {
        val userCode = userManager.getUserCodeSync() ?: "unknown"
        db.collection("sos_sessions").document(userCode).collection("location_updates").add(
            hashMapOf("lat" to lat, "lng" to lng, "timestamp" to System.currentTimeMillis())
        )
    }

    private fun startAudioChunking() {
        recordNextChunk()
    }

    private fun recordNextChunk() {
        if (!isEmergencyActive) return
        
        // --- AUDIO RETRY LOGIC ---
        // Check for failed uploads from previous loops
        retryFailedUploads()

        try {
            currentAudioFile = File(cacheDir, "chunk_${System.currentTimeMillis()}.aac")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentAudioFile?.absolutePath)
                prepare()
                start()
            }
            audioHandler.postDelayed({ stopAndUploadChunk() }, CHUNK_DURATION_MS)
        } catch (e: Exception) {
            Log.e("AudioSystem", "Recording failed", e)
        }
    }

    private fun retryFailedUploads() {
        val files = cacheDir.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".aac") }
        files?.forEach { file ->
            // If the file is not the one currently being recorded
            if (file.absolutePath != currentAudioFile?.absolutePath) {
                Log.d("AudioSystem", "Retrying upload for: ${file.name}")
                uploadAudioToCloudinary(file)
            }
        }
    }

    private fun stopAndUploadChunk() {
        val fileToUpload = currentAudioFile
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            fileToUpload?.let { uploadAudioToCloudinary(it) }
            if (isEmergencyActive) recordNextChunk()
        } catch (e: Exception) {
            Log.e("AudioSystem", "Stop failed", e)
            if (isEmergencyActive) recordNextChunk()
        }
    }

    private fun uploadAudioToCloudinary(file: File) {
        cloudinaryUploader.uploadAudio(file, 
            onSuccess = { url ->
                saveAudioUrlToFirestore(url)
                file.delete() // Only delete on success
            },
            onFailure = { Log.e("AudioSystem", "Upload failed, keeping file for retry: ${file.name}") }
        )
    }

    private fun saveAudioUrlToFirestore(url: String) {
        val userCode = userManager.getUserCodeSync() ?: "unknown"
        db.collection("sos_sessions").document(userCode).collection("audio_chunks").add(
            hashMapOf("audioUrl" to url, "timestamp" to System.currentTimeMillis())
        )
    }

    override fun onDestroy() {
        isEmergencyActive = false
        ServiceState.setEmergencyActive(false)
        ServiceState.setGuardianActive(false)
        sosTriggerManager?.stopDetection()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        audioHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(content).setSmallIcon(android.R.drawable.ic_menu_report_image)
            .setOngoing(true).setContentIntent(pendingIntent).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SoSafe Service", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}