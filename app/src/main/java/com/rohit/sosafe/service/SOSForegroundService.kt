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
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.rohit.sosafe.MainActivity
import com.rohit.sosafe.data.AppModeManager
import com.rohit.sosafe.data.RoleManager
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.data.contracts.*
import com.rohit.sosafe.utils.CloudinaryUploader
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.utils.ServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File

class SOSForegroundService : Service() {

    private val CHANNEL_ID = "SOS_SERVICE_CHANNEL"
    private val NOTIFICATION_ID = 1
    private var sosTriggerManager: SOSTriggerManager? = null
    private var isEmergencyActive = false
    private var sessionId: String = ""
    private var audioSequence = 0
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var userManager: UserManager
    private val db = Firebase.firestore
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cloudinaryUploader = CloudinaryUploader()

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private val audioHandler = Handler(Looper.getMainLooper())
    private val CHUNK_DURATION_MS = 3000L 

    private var sessionListenerRegistration: ListenerRegistration? = null

    companion object {
        const val ACTION_START_EMERGENCY = "ACTION_START_EMERGENCY"
    }

    override fun onCreate() {
        super.onCreate()
        
        val appModeManager = AppModeManager(applicationContext)
        val mode = appModeManager.getAppMode()
        if (mode != null) {
            RoleManager.role = mode.name
        }
        
        userManager = UserManager(applicationContext)
        val myId = userManager.getUserCodeSync()
        if (myId != null) {
            RoleManager.myUserId = myId
        }

        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        if (RoleManager.isSender()) {
            sosTriggerManager = SOSTriggerManager(this)
            sosTriggerManager?.startDetection()
            ServiceState.setGuardianActive(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (RoleManager.isSender()) {
            if (intent?.action == ACTION_START_EMERGENCY) {
                startEmergencyMode()
            } else {
                startGuardianMode()
            }
        } else if (RoleManager.isGuardian()) {
            startGuardianSessionDiscovery()
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

    private fun startGuardianSessionDiscovery() {
        val myCode = userManager.getUserCodeSync() ?: return

        val notification = createNotification("SoSafe Guardian Active", "Monitoring for emergency sessions...")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                val contacts = userManager.getContacts()
                if (contacts.isEmpty()) {
                    Log.d("SOS_AUDIT", "No contacts to monitor.")
                    return@launch
                }

                sessionListenerRegistration?.remove()
                sessionListenerRegistration = db.collection(SoSafeContract.Collections.SESSIONS)
                    .whereIn(SoSafeContract.Fields.SENDER_ID, contacts)
                    .whereEqualTo(SoSafeContract.Fields.STATUS, SoSafeContract.Status.ACTIVE)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            Log.e("SOS_AUDIT", "LISTENER_ERROR: ${e.message}")
                            return@addSnapshotListener
                        }

                        snapshot?.documentChanges?.forEach { change ->
                            if (change.type == DocumentChange.Type.ADDED) {
                                val session = change.document.toObject(SosSession::class.java)
                                showEmergencyNotification(session)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e("SOS_AUDIT", "Failed to start discovery: ${e.message}")
            }
        }
    }

    private fun showEmergencyNotification(session: SosSession) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_report_image)
            .setContentTitle("!!! EMERGENCY SOS !!!")
            .setContentText("User ${session.senderId} needs help!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(1000, 1000, 1000, 1000))
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(session.sessionId.hashCode(), notification)
    }

    private fun startEmergencyMode() {
        if (isEmergencyActive) return
        
        isEmergencyActive = true
        sessionId = "session_" + System.currentTimeMillis()
        audioSequence = 0
        ServiceState.setEmergencyActive(true)
        
        val notification = createNotification("!!! EMERGENCY SOS ACTIVE !!!", "Broadcasting alerts, location and audio.")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        
        serviceScope.launch {
            val myId = userManager.getUserCodeSync() ?: "UNKNOWN"
            
            val sessionData = mapOf(
                SoSafeContract.Fields.SESSION_ID to sessionId,
                SoSafeContract.Fields.SENDER_ID to myId,
                SoSafeContract.Fields.STATUS to SoSafeContract.Status.ACTIVE,
                SoSafeContract.Fields.STARTED_AT to FieldValue.serverTimestamp(),
                SoSafeContract.Fields.LAST_UPDATED_AT to FieldValue.serverTimestamp()
            )

            try {
                db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
                    .set(sessionData).await()
                
                Log.d("SOS_AUDIT", "SESSION_CREATED: $sessionId")
                
                withContext(Dispatchers.Main) {
                    startLocationStreaming()
                    startAudioChunking()
                }
            } catch (e: Exception) {
                Log.e("SOS_AUDIT", "SESSION_CREATE_FAILED: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationStreaming() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { uploadLocation(it.latitude, it.longitude) }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun uploadLocation(lat: Double, lng: Double) {
        val sessionRef = db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
        sessionRef.update(
            mapOf(
                SoSafeContract.Fields.LAST_LOCATION to GeoPoint(lat, lng),
                SoSafeContract.Fields.LAST_UPDATED_AT to FieldValue.serverTimestamp()
            )
        )
    }

    private fun startAudioChunking() {
        recordNextChunk()
    }

    private fun recordNextChunk() {
        if (!isEmergencyActive) return
        try {
            currentAudioFile = File(cacheDir, "chunk_${sessionId}_${audioSequence}.aac")
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
            Log.e("SOS_AUDIT", "AUDIO_RECORD_FAILED: ${e.message}")
        }
    }

    private fun stopAndUploadChunk() {
        val fileToUpload = currentAudioFile
        val sequence = audioSequence++
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            fileToUpload?.let { uploadAudioToCloudinary(it, sequence) }
            if (isEmergencyActive) recordNextChunk()
        } catch (e: Exception) {
            Log.e("SOS_AUDIT", "AUDIO_STOP_FAILED: ${e.message}")
            if (isEmergencyActive) recordNextChunk()
        }
    }

    private fun uploadAudioToCloudinary(file: File, sequence: Int) {
        cloudinaryUploader.uploadAudio(file, 
            onSuccess = { url ->
                saveAudioUrlToFirestore(url, sequence)
                file.delete()
            },
            onFailure = { Log.e("SOS_AUDIT", "CLOUDINARY_UPLOAD_FAILED") }
        )
    }

    private fun saveAudioUrlToFirestore(url: String, sequence: Int) {
        val chunk = mapOf(
            SoSafeContract.Fields.FILE_URL to url,
            SoSafeContract.Fields.SEQUENCE to sequence,
            SoSafeContract.Fields.DURATION to 3,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection(SoSafeContract.getAudioChunksSubcollection(sessionId)).add(chunk)
    }

    override fun onDestroy() {
        serviceScope.launch {
            if (isEmergencyActive && sessionId.isNotEmpty()) {
                db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
                    .update(SoSafeContract.Fields.STATUS, SoSafeContract.Status.ENDED).await()
            }
            serviceScope.cancel()
        }
        
        isEmergencyActive = false
        ServiceState.setEmergencyActive(false)
        ServiceState.setGuardianActive(false)
        sosTriggerManager?.stopDetection()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        audioHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.release()
        sessionListenerRegistration?.remove()
        
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
