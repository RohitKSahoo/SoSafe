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
import android.media.AudioManager
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
import com.rohit.sosafe.utils.RecordingManager
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.utils.ServiceState
import com.rohit.sosafe.utils.WebRTCManager
import kotlinx.coroutines.*
import org.json.JSONObject
import com.rohit.sosafe.data.supabase.SupabaseApi
import java.io.File

class SOSForegroundService : Service() {

    private val CHANNEL_ID = "SOS_SERVICE_CHANNEL"
    private val GUARDIAN_CHANNEL_ID = "SOS_GUARDIAN_CHANNEL_V2"
    private val NOTIFICATION_ID = 1
    private val AUDIT_TAG = "SOS_AUDIT"
    private var sosTriggerManager: SOSTriggerManager? = null
    private var isEmergencyActive = false
    private var sessionId: String = ""
    private var audioSequence = 0
    private var lastKnownLocation: GeoPoint? = null
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var userManager: UserManager
    private val db = Firebase.firestore
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
    private lateinit var recordingManager: RecordingManager
    
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
        Log.d(AUDIT_TAG, "SERVICE_CREATED")
        
        val appModeManager = AppModeManager(applicationContext)
        val mode = appModeManager.getAppMode()
        if (mode != null) {
            RoleManager.role = mode.name
        }
        
        userManager = UserManager(applicationContext)
        streamingModeManager = StreamingModeManager(applicationContext)
        recordingManager = RecordingManager(applicationContext)
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
        
        try {
            val launchIntent = Intent(this, SOSIncomingActivity::class.java).apply {
                putExtra("sessionId", sessionId)
                putExtra("senderId", senderId)
                putExtra("senderName", displayName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(AUDIT_TAG, "Direct launch from service skipped/failed: ${e.message}")
        }
        
        ServiceState.setGuardianActive(true)
        Log.d(AUDIT_TAG, "ALERT_TRIGGERED: Session $sessionId from $senderId")
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

    private var userRealtimeService: com.rohit.sosafe.data.supabase.SupabaseRealtimeClient? = null
    private var sessionsRealtimeService: com.rohit.sosafe.data.supabase.SupabaseRealtimeClient? = null

    private fun startGuardianSessionDiscovery() {
        val myCode = userManager.getUserCodeSync() ?: return

        val notification = createNotification("SoSafe Guardian Active", "Monitoring for emergency sessions...")
        startForeground(NOTIFICATION_ID, notification)

        userRealtimeService?.stop()

        serviceScope.launch(Dispatchers.IO) {
            fetchServiceUserContacts(myCode)
        }

        userRealtimeService = com.rohit.sosafe.data.supabase.SupabaseRealtimeClient("users", "user_id", myCode) { _, _ ->
            serviceScope.launch(Dispatchers.IO) {
                fetchServiceUserContacts(myCode)
            }
        }.apply { start() }
    }

    private fun fetchServiceUserContacts(myCode: String) {
        try {
            val rows = SupabaseApi.select("users", "user_id=eq.$myCode")
            if (rows.length() > 0) {
                val userObj = rows.getJSONObject(0)
                val contactsArr = userObj.optJSONArray("contacts") ?: org.json.JSONArray()
                val namesObj = userObj.optJSONObject("contact_names") ?: org.json.JSONObject()

                val contacts = mutableListOf<String>()
                for (i in 0 until contactsArr.length()) {
                    contacts.add(contactsArr.getString(i))
                }

                val map = mutableMapOf<String, String>()
                val keys = namesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = namesObj.getString(k)
                }
                contactNames = map

                updateSosAlertListener(contacts)
            }
        } catch (e: Exception) {
            Log.e(AUDIT_TAG, "SERVICE_USER_FETCH_ERROR: ${e.message}")
        }
    }

    private fun updateSosAlertListener(contacts: List<String>) {
        sessionsRealtimeService?.stop()
        if (contacts.isEmpty()) {
            Log.d(AUDIT_TAG, "No contacts to monitor in service.")
            return
        }

        Log.d(AUDIT_TAG, "SERVICE_DISCOVERY: Monitoring ${contacts.size} contacts")

        serviceScope.launch(Dispatchers.IO) {
            while (ServiceState.isGuardianActive.value || isEmergencyActive) {
                try {
                    val rows = SupabaseApi.select("sessions", "status=eq.ACTIVE")
                    val activeSessionIds = mutableSetOf<String>()
                    for (i in 0 until rows.length()) {
                        val obj = rows.getJSONObject(i)
                        val sId = obj.optString("session_id")
                        val senderId = obj.optString("sender_id")
                        if (senderId in contacts) {
                            activeSessionIds.add(sId)
                            triggerSosIncomingAlert(
                                sId,
                                senderId,
                                contactNames[senderId] ?: "User ${senderId.take(4)}"
                            )
                        }
                    }
                    // Clean up notifications for sessions that are no longer active
                    val endedSessions = notifiedSessions.filter { it !in activeSessionIds }
                    if (endedSessions.isNotEmpty()) {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        for (eId in endedSessions) {
                            notificationManager.cancel(eId.hashCode())
                        }
                        notifiedSessions.removeAll(endedSessions.toSet())
                    }
                } catch (e: Exception) {
                    Log.e(AUDIT_TAG, "SERVICE_POLL_ERROR: ${e.message}")
                }
                kotlinx.coroutines.delay(3000)
            }
        }

        sessionsRealtimeService = com.rohit.sosafe.data.supabase.SupabaseRealtimeClient("sessions") { type, record ->
            val sId = record.optString("session_id")
            val senderId = record.optString("sender_id")
            val status = record.optString("status")

            if (senderId in contacts) {
                if (type == "INSERT" || type == "UPDATE") {
                    if (status == SoSafeContract.Status.ACTIVE) {
                        triggerSosIncomingAlert(
                            sId, 
                            senderId, 
                            contactNames[senderId] ?: "User ${senderId.take(4)}"
                        )
                    } else if (status == SoSafeContract.Status.ENDED) {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(sId.hashCode())
                        notifiedSessions.remove(sId)
                    }
                }
            }
        }.apply { start() }
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
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(mainPendingIntent)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setSound(soundUri, AudioManager.STREAM_ALARM)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        serviceScope.launch(Dispatchers.Main) {
            startLocationStreaming()
        }

        serviceScope.launch(Dispatchers.IO) {
            val myId = userManager.getUserCodeSync() ?: "UNKNOWN"
            
            // Clean up any stale active sessions for this sender in Supabase
            try {
                val endPreviousJson = JSONObject().apply {
                    put("status", SoSafeContract.Status.ENDED)
                    put("last_updated_at", System.currentTimeMillis())
                }
                SupabaseApi.update("sessions", "sender_id=eq.$myId&status=eq.ACTIVE", endPreviousJson)
            } catch (e: Exception) {
                Log.w(AUDIT_TAG, "STALE_SESSION_CLEANUP_WARN: ${e.message}")
            }
            
            val sessionJson = JSONObject().apply {
                put("session_id", sessionId)
                put("sender_id", myId)
                put("status", SoSafeContract.Status.ACTIVE)
                put("started_at", System.currentTimeMillis())
                put("last_updated_at", System.currentTimeMillis())
                put("streaming_mode", mode.name)
            }

            try {
                SupabaseApi.upsert("sessions", sessionJson, onConflict = "session_id")
                
                Log.d(AUDIT_TAG, "SESSION_CREATED: $sessionId | MODE: $mode")
                notifyGuardiansOfSOS(sessionId, myId)

                withContext(Dispatchers.Main) {
                    if (mode == StreamingMode.HYBRID || mode == StreamingMode.CHUNK_ONLY) {
                        startAudioChunking()
                    }
                    
                    if (mode == StreamingMode.HYBRID || mode == StreamingMode.WEBRTC_ONLY) {
                        startWebRTC()
                    }
                }
            } catch (e: Exception) {
                Log.e(AUDIT_TAG, "SESSION_CREATE_FAILED: ${e.message}")
            }
        }
    }

    private fun startWebRTC() {
        webrtcManager = WebRTCManager(
            context = this,
            sessionId = sessionId,
            onConnectionStateChange = { state ->
                Log.d(AUDIT_TAG, "WEBRTC_STATE: $state")
            }
        )
        webrtcManager?.startSender()
    }

    private fun notifyGuardiansOfSOS(sessionId: String, myId: String) {
        serviceScope.launch {
            try {
                val contacts = userManager.getContacts()
                if (contacts.isEmpty()) return@launch
                Log.d(AUDIT_TAG, "NOTIFYING_GUARDIANS: Found ${contacts.size} guardian contacts")
            } catch (e: Exception) {
                Log.e(AUDIT_TAG, "Error notifying guardians: ${e.message}")
            }
        }
    }

    private fun stopEmergencyMode() {
        if (!isEmergencyActive) return
        
        val sessionToClose = sessionId
        val myId = userManager.getUserCodeSync() ?: "UNKNOWN"
        val finalLocation = lastKnownLocation
        
        isEmergencyActive = false
        ServiceState.setEmergencyActive(false)
        
        Log.d(AUDIT_TAG, "STOP_EMERGENCY_INITIATED: $sessionToClose")
        
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        audioHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.apply {
            try { stop() } catch(e: Exception) {}
            release()
        }
        mediaRecorder = null
        
        webrtcManager?.stop()
        webrtcManager = null
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                withContext(NonCancellable) {
                    // Update Supabase session status to ENDED
                    val statusJson = JSONObject().apply {
                        put("status", SoSafeContract.Status.ENDED)
                        put("last_updated_at", System.currentTimeMillis())
                    }
                    SupabaseApi.update("sessions", "session_id=eq.$sessionToClose", statusJson)
                    
                    Log.d(AUDIT_TAG, "SESSION_STATUS_UPDATED: ENDED ($sessionToClose)")
                    
                    // Finalize local recording on Sender side and save metadata
                    if (finalLocation != null) {
                        recordingManager.saveMetadata(myId, sessionToClose, finalLocation.latitude, finalLocation.longitude, "Me")
                    }
                    recordingManager.finalizeRecording(myId, sessionToClose)
                    Log.d(AUDIT_TAG, "SENDER_LOCAL_FINALIZE_DONE: $sessionToClose")
                }
            } catch (e: Exception) {
                Log.e(AUDIT_TAG, "SESSION_END_PROCESSING_FAILED: ${e.message}")
            }
        }
        
        startGuardianMode()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationStreaming() {
        // Fetch last known location immediately so Guardian gets instant initial location
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            loc?.let {
                lastKnownLocation = GeoPoint(it.latitude, it.longitude)
                uploadLocation(it.latitude, it.longitude)
                Log.d(AUDIT_TAG, "INITIAL_LOCATION_CAPTURED: (${it.latitude}, ${it.longitude})")
            }
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { 
                    lastKnownLocation = GeoPoint(it.latitude, it.longitude)
                    uploadLocation(it.latitude, it.longitude) 
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun uploadLocation(lat: Double, lng: Double) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("last_latitude", lat)
                    put("last_longitude", lng)
                    put("last_updated_at", System.currentTimeMillis())
                }
                SupabaseApi.update("sessions", "session_id=eq.$sessionId", json)
            } catch (e: Exception) {
                Log.e(AUDIT_TAG, "Failed to upload location: ${e.message}")
            }
        }
    }

    private fun startAudioChunking() {
        recordNextChunk()
    }

    private fun recordNextChunk() {
        if (!isEmergencyActive) return

        val sequence = audioSequence++
        val myId = userManager.getUserCodeSync() ?: "UNKNOWN"
        val outputFile = File(cacheDir, "audio_chunk_${sessionId}_$sequence.mp4")
        currentAudioFile = outputFile

        try {
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
            audioHandler.postDelayed({ stopAndUploadChunk() }, CHUNK_DURATION_MS)
        } catch (e: Exception) {
            Log.e(AUDIT_TAG, "AUDIO_RECORD_FAILED: ${e.message}")
        }
    }

    private fun stopAndUploadChunk() {
        val fileToUpload = currentAudioFile
        val sequence = audioSequence
        try {
            mediaRecorder?.apply { 
                try { stop() } catch(e: Exception) { Log.w(AUDIT_TAG, "MediaRecorder stop silent exception: ${e.message}") }
                release() 
            }
            mediaRecorder = null
            
            val myId = userManager.getUserCodeSync() ?: "UNKNOWN"
            if (fileToUpload != null && fileToUpload.exists()) {
                // PERSISTENCE: Save chunk locally before uploading/deleting
                recordingManager.saveChunk(myId, sessionId, sequence, fileToUpload)
                uploadAudioToCloudinary(fileToUpload, sequence)
            }
            
            if (isEmergencyActive) recordNextChunk()
        } catch (e: Exception) {
            Log.e(AUDIT_TAG, "AUDIO_STOP_FAILED: ${e.message}")
            if (isEmergencyActive) recordNextChunk()
        }
    }

    private fun uploadAudioToCloudinary(file: File, sequence: Int) {
        cloudinaryUploader.uploadAudio(file, 
            onSuccess = { url ->
                saveAudioUrlToFirestore(url, sequence)
                file.delete()
            },
            onFailure = { Log.e(AUDIT_TAG, "CLOUDINARY_UPLOAD_FAILED") }
        )
    }

    private fun saveAudioUrlToFirestore(url: String, sequence: Int) {
        serviceScope.launch(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("session_id", sessionId)
                put("file_url", url)
                put("sequence", sequence)
                put("duration", 3)
            }
            SupabaseApi.upsert("audio_chunks", json)
        }
    }

    override fun onDestroy() {
        Log.d(AUDIT_TAG, "SERVICE_DESTROYED")
        val finalSessionId = sessionId
        val wasEmergencyActive = isEmergencyActive
        val myId = userManager.getUserCodeSync() ?: "UNKNOWN"
        
        // STOP ALL ACTIVE LOOPS IMMEDIATELY
        isEmergencyActive = false
        ServiceState.setEmergencyActive(false)
        ServiceState.setGuardianActive(false)
        
        audioHandler.removeCallbacksAndMessages(null)
        mediaRecorder?.apply {
            try { stop() } catch(e: Exception) {}
            release()
        }
        mediaRecorder = null
        
        if (wasEmergencyActive && finalSessionId.isNotEmpty()) {
            Log.w(AUDIT_TAG, "EMERGENCY_ACTIVE_ON_DESTROY: Cleaning up session $finalSessionId")
            cleanupScope.launch {
                try {
                    val updateJson = JSONObject().apply {
                        put("status", SoSafeContract.Status.ENDED)
                    }
                    SupabaseApi.update("sessions", "session_id=eq.$finalSessionId", updateJson)
                    
                    // Abrupt stop: Attempt to finalize what we have
                    RecordingManager(applicationContext).finalizeRecording(myId, finalSessionId)
                } catch (e: Exception) {
                    Log.e(AUDIT_TAG, "ABRUPT_CLEANUP_FAILED: ${e.message}")
                }
            }
        }
        
        sosTriggerManager?.stopDetection()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
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
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()

            val guardianChannel = NotificationChannel(GUARDIAN_CHANNEL_ID, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical alerts for incoming SOS calls"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                setBypassDnd(true)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(guardianChannel)
        }
    }
}
