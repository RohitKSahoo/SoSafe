package com.rohit.sosafe.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import com.rohit.sosafe.data.*
import com.rohit.sosafe.data.contracts.*
import com.rohit.sosafe.ui.SOSIncomingActivity
import com.rohit.sosafe.utils.CloudinaryUploader
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.utils.ServiceState
import com.rohit.sosafe.utils.WebRTCManager
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File

class SOSForegroundService : Service() {

    private val CHANNEL_ID = "SOS_SERVICE_CHANNEL"
    private val GUARDIAN_CHANNEL_ID = "SOS_GUARDIAN_CHANNEL"
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
    private var userListenerRegistration: ListenerRegistration? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var webrtcManager: WebRTCManager? = null
    private lateinit var streamingModeManager: StreamingModeManager
    
    // TRACKING: Prevent duplicate alerts for the same session
    private val notifiedSessions = mutableSetOf<String>()
    
    // Cached contact names for notifications
    private var contactNames: Map<String, String> = emptyMap()

    companion object {
        const val ACTION_START_EMERGENCY = "ACTION_START_EMERGENCY"
        const val ACTION_STOP_EMERGENCY = "ACTION_STOP_EMERGENCY"
        const val ACTION_GUARDIAN_SOS = "ACTION_GUARDIAN_SOS"
    }

    override fun onCreate() {
        super.onCreate()
        
        val appModeManager = AppModeManager(applicationContext)
        val mode = appModeManager.getAppMode()
        if (mode != null) {
            RoleManager.role = mode.name
        }
        
        userManager = UserManager(applicationContext)
        streamingModeManager = StreamingModeManager(applicationContext)
        val myId = userManager.getUserCodeSync()
        if (myId != null) {
            RoleManager.myUserId = myId
        }

        createNotificationChannels()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        if (RoleManager.isSender()) {
            sosTriggerManager = SOSTriggerManager(this)
            sosTriggerManager?.startDetection()
            ServiceState.setGuardianActive(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GUARDIAN_SOS -> {
                val sId = intent.getStringExtra("sessionId") ?: ""
                val sName = intent.getStringExtra("senderName") ?: "Someone"
                val sUserId = intent.getStringExtra("senderId") ?: ""
                triggerSosIncomingAlert(sId, sUserId, sName)
            }
            ACTION_START_EMERGENCY -> {
                if (RoleManager.isSender()) {
                    startEmergencyMode()
                }
            }
            ACTION_STOP_EMERGENCY -> {
                stopEmergencyMode()
            }
            else -> {
                if (RoleManager.isSender()) {
                    startGuardianMode()
                } else if (RoleManager.isGuardian()) {
                    startGuardianSessionDiscovery()
                }
            }
        }
        return START_STICKY
    }

    private fun triggerSosIncomingAlert(sessionId: String, senderId: String, senderName: String) {
        if (notifiedSessions.contains(sessionId)) return
        notifiedSessions.add(sessionId)
        
        acquireWakeLock()
        
        // Use custom name if available in our local cache
        val displayName = contactNames[senderId] ?: senderName
        
        val notification = createFullScreenNotification(sessionId, senderId, displayName)
        
        val alertNotificationId = sessionId.hashCode()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(alertNotificationId, notification)
        
        ServiceState.setGuardianActive(true)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoSafe:SOSWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
        }
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

        userListenerRegistration?.remove()
        userListenerRegistration = db.collection(SoSafeContract.Collections.USERS)
            .document(myCode)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SOS_AUDIT", "SERVICE_USER_LISTENER_ERROR: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val contacts = snapshot.get(SoSafeContract.Fields.CONTACTS) as? List<String> ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    contactNames = snapshot.get(SoSafeContract.Fields.CONTACT_NAMES) as? Map<String, String> ?: emptyMap()
                    
                    updateSosAlertListener(contacts)
                }
            }
    }

    private fun updateSosAlertListener(contacts: List<String>) {
        sessionListenerRegistration?.remove()
        if (contacts.isEmpty()) {
            Log.d("SOS_AUDIT", "No contacts to monitor in service.")
            return
        }

        Log.d("SOS_AUDIT", "SERVICE_DISCOVERY: Monitoring ${contacts.size} contacts")
        sessionListenerRegistration = db.collection(SoSafeContract.Collections.SESSIONS)
            .whereIn(SoSafeContract.Fields.SENDER_ID, contacts)
            .whereEqualTo(SoSafeContract.Fields.STATUS, SoSafeContract.Status.ACTIVE)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SOS_AUDIT", "SERVICE_SESSION_LISTENER_ERROR: ${e.message}")
                    return@addSnapshotListener
                }

                snapshot?.documentChanges?.forEach { change ->
                    when (change.type) {
                        DocumentChange.Type.ADDED -> {
                            val session = change.document.toObject(SosSession::class.java)
                            triggerSosIncomingAlert(
                                session.sessionId, 
                                session.senderId, 
                                "User ${session.senderId.take(4)}"
                            )
                        }
                        DocumentChange.Type.REMOVED -> {
                            val sessionId = change.document.id
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(sessionId.hashCode())
                            notifiedSessions.remove(sessionId)
                            Log.d("SOS_AUDIT", "CANCELLED_NOTIFICATION: Session $sessionId ended.")
                        }
                        else -> {}
                    }
                }
            }
    }

    private fun createFullScreenNotification(sessionId: String, senderId: String, senderName: String): Notification {
        val fullScreenIntent = Intent(this, SOSIncomingActivity::class.java).apply {
            putExtra("sessionId", sessionId)
            putExtra("senderId", senderId)
            putExtra("senderName", senderName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + packageName + "/raw/siren")

        return NotificationCompat.Builder(this, GUARDIAN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_report_image)
            .setContentTitle("INCOMING SOS ALERT")
            .setContentText("$senderName is in danger!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(mainPendingIntent)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setSound(soundUri)
            .build()
    }

    private fun startEmergencyMode() {
        if (isEmergencyActive) return
        
        isEmergencyActive = true
        sessionId = "session_" + System.currentTimeMillis()
        audioSequence = 0
        ServiceState.setEmergencyActive(true)
        
        val mode = streamingModeManager.getStreamingMode()
        
        val notification = createNotification("!!! EMERGENCY SOS ACTIVE !!!", "Broadcasting alerts, location and audio ($mode).")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        
        serviceScope.launch {
            val myId = userManager.getUserCodeSync() ?: "UNKNOWN"
            
            val sessionData = mapOf(
                SoSafeContract.Fields.SESSION_ID to sessionId,
                SoSafeContract.Fields.SENDER_ID to myId,
                SoSafeContract.Fields.STATUS to SoSafeContract.Status.ACTIVE,
                SoSafeContract.Fields.STARTED_AT to FieldValue.serverTimestamp(),
                SoSafeContract.Fields.LAST_UPDATED_AT to FieldValue.serverTimestamp(),
                SoSafeContract.Fields.STREAMING_MODE to mode.name
            )

            try {
                db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
                    .set(sessionData).await()
                
                Log.d("SOS_AUDIT", "SESSION_CREATED: $sessionId | MODE: $mode")
                notifyGuardiansOfSOS(sessionId, myId)

                withContext(Dispatchers.Main) {
                    startLocationStreaming()
                    
                    if (mode == StreamingMode.HYBRID || mode == StreamingMode.CHUNK_ONLY) {
                        startAudioChunking()
                    }
                    
                    if (mode == StreamingMode.HYBRID || mode == StreamingMode.WEBRTC_ONLY) {
                        startWebRTC()
                    }
                }
            } catch (e: Exception) {
                Log.e("SOS_AUDIT", "SESSION_CREATE_FAILED: ${e.message}")
            }
        }
    }

    private fun startWebRTC() {
        webrtcManager = WebRTCManager(
            context = this,
            sessionId = sessionId,
            onConnectionStateChange = { state ->
                Log.d("SOS_AUDIT", "WebRTC State: $state")
            }
        )
        webrtcManager?.startSender()
    }

    private fun notifyGuardiansOfSOS(sessionId: String, myId: String) {
        serviceScope.launch {
            try {
                val contacts = userManager.getContacts()
                if (contacts.isEmpty()) return@launch

                val guardianDocs = db.collection(SoSafeContract.Collections.USERS)
                    .whereIn(SoSafeContract.Fields.USER_ID, contacts)
                    .get()
                    .await()

                val tokens = guardianDocs.documents.mapNotNull { it.getString(SoSafeContract.Fields.FCM_TOKEN) }
                if (tokens.isNotEmpty()) {
                    Log.d("SOS_AUDIT", "NOTIFYING_GUARDIANS: Found ${tokens.size} tokens")
                }
            } catch (e: Exception) {
                Log.e("SOS_AUDIT", "Error notifying guardians: ${e.message}")
            }
        }
    }

    private fun stopEmergencyMode() {
        if (!isEmergencyActive) return
        
        val sessionToClose = sessionId
        isEmergencyActive = false
        ServiceState.setEmergencyActive(false)
        
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        audioHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.apply {
            try { stop() } catch(e: Exception) {}
            release()
        }
        mediaRecorder = null
        
        webrtcManager?.stop()
        webrtcManager = null
        
        serviceScope.launch {
            try {
                db.collection(SoSafeContract.Collections.SESSIONS).document(sessionToClose)
                    .update(SoSafeContract.Fields.STATUS, SoSafeContract.Status.ENDED).await()
                Log.d("SOS_AUDIT", "SESSION_ENDED_BY_USER: $sessionToClose")
            } catch (e: Exception) {
                Log.e("SOS_AUDIT", "SESSION_END_FAILED: ${e.message}")
            }
        }
        
        startGuardianMode()
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
        val finalSessionId = sessionId
        val wasEmergencyActive = isEmergencyActive
        
        if (wasEmergencyActive && finalSessionId.isNotEmpty()) {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    Firebase.firestore.collection(SoSafeContract.Collections.SESSIONS)
                        .document(finalSessionId)
                        .update(SoSafeContract.Fields.STATUS, SoSafeContract.Status.ENDED)
                        .await()
                } catch (e: Exception) {}
            }
        }
        
        isEmergencyActive = false
        ServiceState.setEmergencyActive(false)
        ServiceState.setGuardianActive(false)
        sosTriggerManager?.stopDetection()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        audioHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.release()
        webrtcManager?.stop()
        sessionListenerRegistration?.remove()
        userListenerRegistration?.remove()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        serviceScope.cancel()
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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val serviceChannel = NotificationChannel(CHANNEL_ID, "SoSafe Service", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(serviceChannel)
            
            val soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + packageName + "/raw/siren")
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val guardianChannel = NotificationChannel(GUARDIAN_CHANNEL_ID, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical alerts for incoming SOS calls"
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(guardianChannel)
        }
    }
}
