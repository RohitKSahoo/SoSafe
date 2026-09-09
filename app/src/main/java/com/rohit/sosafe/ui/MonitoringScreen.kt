package com.rohit.sosafe.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
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
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordingManager = remember { RecordingManager(context) }
    
    val isPlayback = playbackInfo != null
    
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
    var webrtcState by remember { mutableStateOf(PeerConnection.PeerConnectionState.NEW) }
    val isWebRTCActive = webrtcState == PeerConnection.PeerConnectionState.CONNECTED

    // Helper to force speakerphone routing
    val forceSpeakerphone = {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            // MODE_NORMAL is essential for dual-speaker media playback
            audioManager.mode = AudioManager.MODE_NORMAL
            
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

    // Location Listener (Bound to Session State)
    DisposableEffect(sessionState) {
        if (sessionState !is SessionState.ACTIVE || isPlayback) return@DisposableEffect onDispose {}
        
        val activeSession = sessionState as SessionState.ACTIVE
        val client = SupabaseRealtimeClient("sessions", "session_id", activeSession.sessionId) { _, record ->
            val lat = record.optDouble("last_latitude", Double.NaN)
            val lng = record.optDouble("last_longitude", Double.NaN)
            if (!lat.isNaN() && !lng.isNaN()) {
                lastLocation = com.google.firebase.firestore.GeoPoint(lat, lng)
            }
        }.apply { start() }

        onDispose { client.stop() }
    }

    // Audio Chunk Listener (Bound to Session State)
    var isInitialSnapshotLoaded by remember { mutableStateOf(false) }
    var maxInitialSequence by remember { mutableIntStateOf(-1) }

    DisposableEffect(sessionState, isWebRTCActive) {
        if (sessionState !is SessionState.ACTIVE || isPlayback) return@DisposableEffect onDispose {}
        
        val activeSession = sessionState as SessionState.ACTIVE

        // Initial fetch
        scope.launch(Dispatchers.IO) {
            try {
                val rows = SupabaseApi.select("audio_chunks", "session_id=eq.${activeSession.sessionId}&order=sequence.asc")
                var maxSeq = -1
                for (i in 0 until rows.length()) {
                    val seq = rows.getJSONObject(i).optInt("sequence", -1)
                    if (seq > maxSeq) maxSeq = seq
                }
                maxInitialSequence = maxSeq
                isInitialSnapshotLoaded = true
                Log.d("MonitoringScreen", "Initial chunk snapshot loaded via Supabase. Max initial sequence: $maxInitialSequence")
            } catch (e: Exception) {
                Log.e("MonitoringScreen", "Error fetching initial chunks: ${e.message}")
            }
        }

        val client = SupabaseRealtimeClient("audio_chunks", "session_id", activeSession.sessionId) { eventType, record ->
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
                    recordingManager.downloadAndSaveChunk(
                        session!!.senderId,
                        session.sessionId,
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

    // UI Rendering
    Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    
    Box(modifier = Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        val mapView = remember { MapView(context) }
        val markerState = remember { mutableStateOf<Marker?>(null) }

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
                mapView.onDetach() 
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkGrey.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (displayName.isNotBlank()) displayName else "USER ID: ${session?.senderId ?: "N/A"}", 
                        color = PureWhite, 
                        fontWeight = FontWeight.Bold
                    )
                    
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

                    if (isPlayback && playbackInfo != null) {
                        PlaybackPlayer(file = playbackInfo.file)
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
fun PlaybackPlayer(file: File) {
    val context = LocalContext.current
    val mediaPlayer = remember(file) { 
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
        }
    }
    
    var isPlaying by remember { mutableStateOf(false) }
    val duration by remember { mutableStateOf(mediaPlayer.duration) }
    var position by remember { mutableStateOf(0) }

    DisposableEffect(file) {
        mediaPlayer.setOnCompletionListener { isPlaying = false }
        onDispose { 
            mediaPlayer.release() 
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            position = mediaPlayer.currentPosition
            delay(500)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Black)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            if (isPlaying) mediaPlayer.pause() else mediaPlayer.start()
            isPlaying = !isPlaying
        }) {
            Icon(
                if (isPlaying) Icons.Default.Close else Icons.Default.Mic, 
                contentDescription = null, 
                tint = SuccessGreen
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = if (isPlaying) "PLAYING RECORDING..." else "RECORDING READY",
                color = SuccessGreen,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = { if (duration > 0) position.toFloat() / duration else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 4.dp),
                color = SuccessGreen,
                trackColor = MediumGrey
            )
        }
    }
}
