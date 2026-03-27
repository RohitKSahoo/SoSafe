package com.rohit.sosafe.ui

import android.content.Context
import android.media.MediaPlayer
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
import androidx.compose.ui.graphics.Color
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
import com.rohit.sosafe.data.RoleManager
import com.rohit.sosafe.data.contracts.*
import com.rohit.sosafe.ui.theme.*
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MonitoringScreen(
    session: SosSession,
    initialDelayMillis: Long = 0L,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val db = Firebase.firestore
    var lastLocation by remember { mutableStateOf(session.lastLocation) }
    
    val audioQueue = remember { mutableStateListOf<AudioChunk>() }
    val playedSequences = remember { mutableSetOf<Int>() }

    Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    Configuration.getInstance().userAgentValue = context.packageName

    // STEP 4: FLATTENED LOCATION SYSTEM (Document Listener)
    // Also handles auto-closing when session ends
    DisposableEffect(session.sessionId) {
        val registration = db.collection(SoSafeContract.Collections.SESSIONS)
            .document(session.sessionId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SOS_AUDIT", "LISTENER_ERROR (Location): ${e.message}", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val updatedSession = snapshot.toObject(SosSession::class.java)
                    
                    // AUTO-CLOSE Logic
                    if (updatedSession?.status == SoSafeContract.Status.ENDED) {
                        Log.d("SOS_AUDIT", "SESSION_ENDED: Closing monitoring screen.")
                        onClose()
                        return@addSnapshotListener
                    }

                    if (updatedSession?.lastLocation != null) {
                        lastLocation = updatedSession.lastLocation
                        Log.d("SOS_AUDIT", "LOCATION_WRITE (Received): ${lastLocation?.latitude},${lastLocation?.longitude}")
                    }
                } else if (snapshot != null && !snapshot.exists()) {
                    // Session deleted or finished
                    onClose()
                }
            }
        onDispose { registration.remove() }
    }

    // STEP 5: SEQUENCE-BASED AUDIO SYSTEM
    DisposableEffect(session.sessionId) {
        val registration = db.collection(SoSafeContract.getAudioChunksSubcollection(session.sessionId))
            .orderBy(SoSafeContract.Fields.SEQUENCE, Query.Direction.ASCENDING)
            .limit(20)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("SOS_AUDIT", "LISTENER_ERROR (Audio): ${e.message}", e)
                    return@addSnapshotListener
                }

                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val chunk = change.document.toObject(AudioChunk::class.java)
                        if (!playedSequences.contains(chunk.sequence)) {
                            Log.d("SOS_AUDIT", "AUDIO_WRITE (Received): seq=${chunk.sequence}")
                            playedSequences.add(chunk.sequence)
                            audioQueue.add(chunk)
                        }
                    }
                }
            }
        onDispose { registration.remove() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding() // FIX: Added to prevent map and UI elements from overlapping with system bars
    ) {
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
                            title = "SENDER LOCATION"
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = DangerRed,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(PureWhite, RoundedCornerShape(4.dp)))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("LIVE MONITORING", color = PureWhite, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                    Text("USER ID: ${session.senderId}", color = PureWhite, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (lastLocation != null) "GPS: LIVE" else "WAITING FOR GPS...",
                            color = if (lastLocation != null) SuccessGreen else LightGrey,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    AudioQueuePlayer(audioQueue, initialDelayMillis)
                }
            }
        }
    }
}

@Composable
fun AudioQueuePlayer(queue: MutableList<AudioChunk>, initialDelayMillis: Long = 0L) {
    val mediaPlayer = remember { MediaPlayer() }
    var isCurrentlyPlaying by remember { mutableStateOf(false) }
    var isDelayFinished by remember { mutableStateOf(initialDelayMillis == 0L) }

    if (initialDelayMillis > 0) {
        LaunchedEffect(Unit) {
            delay(initialDelayMillis)
            isDelayFinished = true
        }
    }

    LaunchedEffect(queue.size, isCurrentlyPlaying, isDelayFinished) {
        if (isDelayFinished && !isCurrentlyPlaying && queue.isNotEmpty()) {
            val nextChunk = queue.removeAt(0)
            try {
                isCurrentlyPlaying = true
                mediaPlayer.reset()
                mediaPlayer.setDataSource(nextChunk.fileUrl)
                mediaPlayer.prepareAsync()
                mediaPlayer.setOnPreparedListener { it.start() }
                mediaPlayer.setOnCompletionListener {
                    isCurrentlyPlaying = false
                }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    Log.e("SOS_AUDIT", "MediaPlayer error: $what, $extra")
                    isCurrentlyPlaying = false
                    true
                }
            } catch (e: Exception) {
                Log.e("SOS_AUDIT", "Audio play failed", e)
                isCurrentlyPlaying = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { mediaPlayer.release() }
    }

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
            tint = if (isCurrentlyPlaying) DangerRed else if (!isDelayFinished) LightGrey else SuccessGreen
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (!isDelayFinished) "INITIALIZING SECURE FEED..."
                   else if (isCurrentlyPlaying) "PLAYING LIVE AUDIO FEED..." 
                   else if (queue.isNotEmpty()) "BUFFERING (${queue.size} CHUNKS)..."
                   else "AWAITING AUDIO FEED",
            color = if (isCurrentlyPlaying) DangerRed else if (!isDelayFinished) LightGrey else SuccessGreen,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
