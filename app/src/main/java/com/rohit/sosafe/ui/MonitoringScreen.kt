package com.rohit.sosafe.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import androidx.activity.compose.BackHandler
import com.rohit.sosafe.architecture.AudioPlaybackController
import com.rohit.sosafe.architecture.SessionController
import com.rohit.sosafe.architecture.SessionState
import com.rohit.sosafe.data.contracts.*
import com.rohit.sosafe.ui.theme.*
import com.rohit.sosafe.utils.RecordingInfo
import com.rohit.sosafe.utils.RecordingManager
import com.rohit.sosafe.utils.WebRTCManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import android.widget.Toast
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import org.webrtc.PeerConnection
import java.io.File

import com.rohit.sosafe.data.supabase.SupabaseApi
import com.rohit.sosafe.data.supabase.SupabaseRealtimeClient
import kotlinx.coroutines.Dispatchers

@Composable
fun MonitoringScreen(
    session: SosSession? = null,
    playbackInfo: RecordingInfo? = null,
    displayName: String = "",
    initialDelayMillis: Long = 0L,
    isSirenActive: Boolean = false,
    onSilenceSiren: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordingManager = remember { RecordingManager(context) }
    
    val isPlayback = playbackInfo != null
    
    BackHandler {
        onClose()
    }
    
    // NEW ARCHITECTURE: Controllers
    val sessionController = remember(session?.sessionId) {
        if (session != null && !isPlayback) {
            SessionController(session.sessionId)
        } else null
    }
    
    val sessionState by if (sessionController != null) {
        sessionController.sessionState.collectAsState()
    } else {
        remember { mutableStateOf(SessionState.IDLE) }
    }
    
    val playbackController = remember(session?.sessionId) {
        if (sessionController != null) {
            AudioPlaybackController(context, sessionController.sessionState)
        } else null
    }

    var lastLocation by remember { mutableStateOf(playbackInfo?.lastLocation ?: session?.lastLocation) }

    // WebRTC State
    var webrtcState by remember(session?.sessionId) { mutableStateOf(PeerConnection.PeerConnectionState.NEW) }
    var remoteVideoTrack by remember(session?.sessionId) { mutableStateOf<org.webrtc.VideoTrack?>(null) }
    var pipOffsetX by remember { mutableFloatStateOf(0f) }
    var pipOffsetY by remember { mutableFloatStateOf(0f) }
    val isWebRTCActive = webrtcState == PeerConnection.PeerConnectionState.CONNECTED

    // Helper to force speakerphone routing
    val forceSpeakerphone = {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val speaker = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            }
            audioManager.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.e("MonitoringScreen", "Failed to force speakerphone: ${e.message}")
        }
    }

    val webrtcManager = remember(session?.sessionId ?: playbackInfo?.sessionId) {
        if (session != null && !isPlayback) {
            WebRTCManager(
                context = context,
                sessionId = session.sessionId,
                isReceiver = true, // Force use of Media Stream for dual speakers
                onConnectionStateChange = { newState ->
                    webrtcState = newState
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        forceSpeakerphone()
                    }
                },
                onAudioTrackReceived = { track ->
                    track.setEnabled(true)
                    forceSpeakerphone()
                },
                onVideoTrackReceived = { videoTrack ->
                    remoteVideoTrack = videoTrack
                }
            )
        } else null
    }

    // Lifecycle Management
    LaunchedEffect(session?.sessionId) {
        if (!isPlayback) {
            forceSpeakerphone()
            
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxMusicVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVolume, 0)
            val maxVoiceVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceVolume, 0)
            
            sessionController?.startMonitoring()
            webrtcManager?.startReceiver()
        }
    }

    DisposableEffect(session?.sessionId) {
        onDispose {
            sessionController?.stopMonitoring()
            playbackController?.release()
            webrtcManager?.stop()
            
            // Restore audio settings
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }

    var locationHistoryPoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

    // Location Listener (Bound to Session State or Playback)
    DisposableEffect(sessionState, session?.sessionId ?: playbackInfo?.sessionId) {
        val sId = session?.sessionId ?: playbackInfo?.sessionId ?: return@DisposableEffect onDispose {}
        
        // Initial fetch of location history and last location
        scope.launch(Dispatchers.IO) {
            try {
                val rows = SupabaseApi.select("sessions", "session_id=eq.$sId")
                if (rows.length() > 0) {
                    val obj = rows.getJSONObject(0)
                    val lastLat = obj.optDouble("last_latitude", Double.NaN)
                    val lastLng = obj.optDouble("last_longitude", Double.NaN)
                    if (!lastLat.isNaN() && !lastLng.isNaN()) {
                        lastLocation = com.google.firebase.firestore.GeoPoint(lastLat, lastLng)
                    }

                    val historyArr = obj.optJSONArray("location_history") ?: org.json.JSONArray()
                    val pointsList = mutableListOf<GeoPoint>()
                    for (i in 0 until historyArr.length()) {
                        val pObj = historyArr.optJSONObject(i) ?: continue
                        val lat = pObj.optDouble("lat", Double.NaN)
                        val lng = pObj.optDouble("lng", Double.NaN)
                        if (!lat.isNaN() && !lng.isNaN()) {
                            pointsList.add(GeoPoint(lat, lng))
                        }
                    }
                    if (pointsList.isNotEmpty()) {
                        locationHistoryPoints = pointsList
                        if (lastLocation == null) {
                            lastLocation = com.google.firebase.firestore.GeoPoint(pointsList.last().latitude, pointsList.last().longitude)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MonitoringScreen", "Error loading location history: ${e.message}")
            }
        }

        val client = SupabaseRealtimeClient("sessions", "session_id", sId) { _, record ->
            val lat = record.optDouble("last_latitude", Double.NaN)
            val lng = record.optDouble("last_longitude", Double.NaN)
            if (!lat.isNaN() && !lng.isNaN()) {
                lastLocation = com.google.firebase.firestore.GeoPoint(lat, lng)
            }

            val historyArr = record.optJSONArray("location_history") ?: org.json.JSONArray()
            val pointsList = mutableListOf<GeoPoint>()
            for (i in 0 until historyArr.length()) {
                val pObj = historyArr.optJSONObject(i) ?: continue
                val pLat = pObj.optDouble("lat", Double.NaN)
                val pLng = pObj.optDouble("lng", Double.NaN)
                if (!pLat.isNaN() && !pLng.isNaN()) {
                    pointsList.add(GeoPoint(pLat, pLng))
                }
            }
            if (pointsList.isNotEmpty()) {
                locationHistoryPoints = pointsList
            }
        }.apply { start() }

        onDispose { client.stop() }
    }

    // Audio Chunk Listener (Bound to Session State)
    var isInitialSnapshotLoaded by remember { mutableStateOf(false) }
    var maxInitialSequence by remember { mutableIntStateOf(-1) }

    DisposableEffect(sessionState, isWebRTCActive) {
        val sId = session?.sessionId ?: playbackInfo?.sessionId ?: return@DisposableEffect onDispose {}

        // Initial fetch
        scope.launch(Dispatchers.IO) {
            try {
                val rows = SupabaseApi.select("audio_chunks", "session_id=eq.$sId&order=sequence.asc")
                var maxSeq = -1
                var latestChunk: AudioChunk? = null
                for (i in 0 until rows.length()) {
                    val obj = rows.getJSONObject(i)
                    val seq = obj.optInt("sequence", -1)
                    if (seq > maxSeq) {
                        maxSeq = seq
                        val fileUrl = obj.optString("file_url")
                        val duration = obj.optInt("duration", 3)
                        val createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        latestChunk = AudioChunk(fileUrl = fileUrl, sequence = seq, duration = duration, createdAt = createdAt)
                    }
                }
                maxInitialSequence = maxSeq
                isInitialSnapshotLoaded = true

                if (latestChunk != null && !isWebRTCActive) {
                    val age = System.currentTimeMillis() - ((latestChunk.createdAt as? Long) ?: 0L)
                    if (age < 12000L) {
                        withContext(Dispatchers.Main) {
                            playbackController?.enqueue(latestChunk)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MonitoringScreen", "Error fetching initial chunks: ${e.message}")
            }
        }

        val client = SupabaseRealtimeClient("audio_chunks", "session_id", sId) { eventType, record ->
            if (eventType == "INSERT" || eventType == "UPDATE") {
                val fileUrl = record.optString("file_url")
                val seq = record.optInt("sequence", 0)
                val duration = record.optInt("duration", 3)
                val createdAt = record.optLong("created_at", System.currentTimeMillis())

                val chunk = AudioChunk(
                    fileUrl = fileUrl,
                    sequence = seq,
                    duration = duration,
                    createdAt = createdAt
                )

                if (seq > maxInitialSequence) {
                    if (!isWebRTCActive) {
                        playbackController?.enqueue(chunk)
                    }
                }

                scope.launch {
                    val senderId = session?.senderId ?: "GUARDIAN"
                    recordingManager.downloadAndSaveChunk(
                        senderId,
                        sId,
                        chunk.sequence,
                        chunk.fileUrl
                    )
                }
            }
        }.apply { start() }

        onDispose { 
            client.stop()
            isInitialSnapshotLoaded = false
            maxInitialSequence = -1
        }
    }

    var showVideoFeedScreen by remember { mutableStateOf(false) }

    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.ENDED) {
            remoteVideoTrack = null
            showVideoFeedScreen = false
            onSilenceSiren?.invoke()
        }
    }

    if (showVideoFeedScreen) {
        VideoFeedScreen(
            videoTrack = remoteVideoTrack,
            displayName = displayName,
            onSwitchCamera = { webrtcManager?.switchCamera() },
            onBack = { showVideoFeedScreen = false }
        )
    } else {
        // UI Rendering
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        
        Box(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
            val mapView = remember { MapView(context) }
            val markerState = remember { mutableStateOf<Marker?>(null) }
            val polylineState = remember { mutableStateOf<Polyline?>(null) }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> mapView.onResume()
                        Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { 
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    try {
                        mapView.onPause()
                        mapView.onDetach() 
                    } catch (e: Exception) {
                        Log.e("MonitoringScreen", "Error detaching mapView: ${e.message}")
                    }
                }
            }

            AndroidView(
                factory = { 
                    mapView.apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(19.0)
                    }
                },
                update = { view ->
                    if (locationHistoryPoints.isNotEmpty()) {
                        if (polylineState.value == null) {
                            polylineState.value = Polyline().apply {
                                outlinePaint.color = android.graphics.Color.RED
                                outlinePaint.strokeWidth = 10f
                                view.overlays.add(this)
                            }
                        }
                        polylineState.value?.setPoints(locationHistoryPoints)
                    }

                    lastLocation?.let { firePoint ->
                        val osmPoint = GeoPoint(firePoint.latitude, firePoint.longitude)
                        if (markerState.value == null) {
                            markerState.value = Marker(view).apply {
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = displayName.ifBlank { "LAST KNOWN LOCATION" }
                                icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_mylocation)
                                view.overlays.add(this)
                            }
                        }
                        markerState.value?.position = osmPoint
                        view.controller.animateTo(osmPoint)
                        view.invalidate()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay UI
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = when {
                    isPlayback -> MediumGrey
                    sessionState is SessionState.ACTIVE && isWebRTCActive -> SuccessGreen
                    sessionState is SessionState.ACTIVE -> DangerRed
                    else -> DarkGrey
                }

                Surface(color = statusColor, shape = RoundedCornerShape(4.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(if (sessionState is SessionState.ACTIVE) PureWhite else LightGrey, RoundedCornerShape(4.dp)))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isPlayback -> "ARCHIVED SESSION"
                                sessionState is SessionState.ENDED -> "SOS ENDED - SESSION REVIEW"
                                sessionState is SessionState.TERMINATING -> "ENDING SESSION..."
                                isWebRTCActive -> "LIVE AUDIO (WEBRTC)"
                                sessionState is SessionState.ACTIVE -> "MONITORING (CHUNKS)"
                                else -> "CONNECTING..."
                            }, 
                            color = PureWhite, 
                            style = MaterialTheme.typography.labelMedium, 
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Black.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = PureWhite)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isSirenActive && onSilenceSiren != null) {
                Button(
                    onClick = onSilenceSiren,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerRed,
                        contentColor = PureWhite
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Silence Siren",
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SILENCE SIREN ALERT",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            if (!isPlayback && sessionState is SessionState.ACTIVE && isWebRTCActive && remoteVideoTrack != null) {
                Button(
                    onClick = { showVideoFeedScreen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = Black, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("📹 CHECK LIVE VIDEO STREAM", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkGrey.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val dateStr = remember(playbackInfo, session) {
                        val ts: Long = playbackInfo?.timestamp ?: when (val s = session?.startedAt) {
                            is Long -> s
                            is Number -> s.toLong()
                            else -> System.currentTimeMillis()
                        }
                        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                        sdf.format(java.util.Date(ts))
                    }
                    val headerTitle = when {
                        displayName.isNotBlank() -> "$displayName ($dateStr)"
                        session?.senderId != null -> "User ${session.senderId.take(4)} ($dateStr)"
                        else -> "SOS RECORDING ($dateStr)"
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = headerTitle, 
                            color = PureWhite, 
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = if (sessionState is SessionState.ACTIVE) SuccessGreen else LightGrey, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isPlayback -> "GPS: ARCHIVED"
                                sessionState is SessionState.ACTIVE -> if (lastLocation != null) "GPS: LIVE" else "WAITING FOR GPS..."
                                else -> "GPS: LAST KNOWN"
                            },
                            color = if (sessionState is SessionState.ACTIVE && lastLocation != null) SuccessGreen else LightGrey,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    var stitchedFile by remember { mutableStateOf<File?>(null) }
                    var isFinalizingRecording by remember { mutableStateOf(false) }

                    LaunchedEffect(sessionState) {
                        if (sessionState is SessionState.ENDED) {
                            val senderId = session?.senderId ?: "GUARDIAN"
                            val sId = session?.sessionId ?: playbackInfo?.sessionId ?: ""
                            if (sId.isNotBlank()) {
                                isFinalizingRecording = true
                                stitchedFile = withContext(Dispatchers.IO) {
                                    try {
                                        recordingManager.finalizeRecording(senderId, sId)
                                    } catch (e: Exception) {
                                        Log.e("MonitoringScreen", "Error finalizing recording: ${e.message}")
                                        null
                                    }
                                }
                                isFinalizingRecording = false
                            }
                        }
                    }

                    if (playbackInfo != null) {
                        PlaybackPlayer(file = playbackInfo.file, senderDisplayName = displayName)
                    } else if (sessionState is SessionState.ENDED) {
                        val fileToPlay = stitchedFile
                        if (fileToPlay != null) {
                            PlaybackPlayer(file = fileToPlay, senderDisplayName = displayName)
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Black)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PureWhite, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isFinalizingRecording) "STITCHING RECORDING..." else "PREPARING AUDIO RECORDING...",
                                    color = LightGrey,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        StatusPlayerUI(
                            sessionState = sessionState,
                            isWebRTCActive = isWebRTCActive
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
fun StatusPlayerUI(
    sessionState: SessionState,
    isWebRTCActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Black)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Mic, 
            contentDescription = null, 
            tint = if (isWebRTCActive) SuccessGreen else if (sessionState is SessionState.ACTIVE) DangerRed else LightGrey
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = when {
                isWebRTCActive -> "LIVE AUDIO FEED (WEBRTC ACTIVE)"
                sessionState is SessionState.CONNECTING -> "INITIALIZING SECURE FEED..."
                sessionState is SessionState.ACTIVE -> "PLAYING BACKUP AUDIO FEED..."
                sessionState is SessionState.ENDED -> "AUDIO FEED TERMINATED"
                else -> "AWAITING AUDIO FEED"
            },
            color = if (isWebRTCActive || sessionState is SessionState.ACTIVE) SuccessGreen else if (sessionState is SessionState.ENDED) DangerRed else LightGrey,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PlaybackPlayer(file: File, senderDisplayName: String = "") {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var isPrepared by remember { mutableStateOf(false) }

    val mediaPlayer = remember(file) { MediaPlayer() }

    DisposableEffect(file) {
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener { mp ->
                duration = mp.duration
                isPrepared = true
            }
            mediaPlayer.setOnCompletionListener { 
                isPlaying = false 
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Log.e("PlaybackPlayer", "Error preparing media: ${e.message}")
        }

        onDispose { 
            try {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.stop()
                }
                mediaPlayer.release()
            } catch (e: Exception) {
                Log.e("PlaybackPlayer", "Error releasing media player: ${e.message}")
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try {
                if (isPrepared) {
                    position = mediaPlayer.currentPosition
                }
            } catch (e: Exception) {}
            delay(200)
        }
    }

    fun formatMs(ms: Int): String {
        val totalSec = Math.max(0, ms / 1000)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Black)
            .padding(12.dp)
    ) {
        Text(
            text = if (isPlaying) "PLAYING RECORDING..." else "RECORDING READY",
            color = SuccessGreen,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        // Progress Slider with Seek Capability
        Slider(
            value = if (duration > 0) position.toFloat() else 0f,
            onValueChange = { newPos ->
                position = newPos.toInt()
                mediaPlayer.seekTo(position)
            },
            valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
            colors = SliderDefaults.colors(
                thumbColor = SuccessGreen,
                activeTrackColor = SuccessGreen,
                inactiveTrackColor = MediumGrey
            ),
            modifier = Modifier.fillMaxWidth().height(24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatMs(position), color = LightGrey, style = MaterialTheme.typography.labelSmall)
            Text(text = formatMs(duration), color = LightGrey, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Controls Row: Play/Pause, -10s, +10s, and Export Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Rewind -10s
                IconButton(onClick = {
                    val newPos = Math.max(0, mediaPlayer.currentPosition - 10000)
                    mediaPlayer.seekTo(newPos)
                    position = newPos
                }) {
                    Text("-10s", color = PureWhite, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }

                // Play / Pause
                IconButton(onClick = {
                    if (isPlaying) mediaPlayer.pause() else mediaPlayer.start()
                    isPlaying = !isPlaying
                }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                        contentDescription = null, 
                        tint = SuccessGreen,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Forward +10s
                IconButton(onClick = {
                    val newPos = Math.min(duration, mediaPlayer.currentPosition + 10000)
                    mediaPlayer.seekTo(newPos)
                    position = newPos
                }) {
                    Text("+10s", color = PureWhite, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = {
                    try {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val sanitizedSenderName = senderDisplayName.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "Sender" }
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                        val timeStr = sdf.format(java.util.Date(file.lastModified()))
                        val customFileName = "${sanitizedSenderName}_${timeStr}.m4a"
                        val saveFile = File(downloadsDir, customFileName)
                        file.copyTo(saveFile, overwrite = true)
                        Toast.makeText(context, "Saved to Downloads: ${saveFile.name}", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DarkGrey, contentColor = PureWhite),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("SAVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}
