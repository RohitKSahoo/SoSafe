package com.rohit.sosafe.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rohit.sosafe.ui.theme.*
import com.rohit.sosafe.utils.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import com.rohit.sosafe.utils.RecordingManager
import com.rohit.sosafe.utils.WebRtcVideoRecorder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VideoFeedScreen(
    videoTrack: VideoTrack?,
    displayName: String,
    sessionId: String = "",
    userId: String = "",
    onSwitchCamera: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    var isCapturing by remember { mutableStateOf(false) }
    var surfaceViewRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    val recordingManager = remember { RecordingManager(context) }
    val videoFile = remember(userId, sessionId) {
        if (userId.isNotBlank() && sessionId.isNotBlank()) {
            recordingManager.getVideoFile(userId, sessionId)
        } else {
            File(context.getExternalFilesDir(null), "recordings/live_${System.currentTimeMillis()}.mp4")
        }
    }

    var isRecordingActive by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableStateOf(0) }
    val recorderRef = remember { mutableStateOf<WebRtcVideoRecorder?>(null) }

    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            recordingDurationSec = 0
            while (isRecordingActive) {
                kotlinx.coroutines.delay(1000L)
                recordingDurationSec++
            }
        }
    }

    // Auto-start recording when live videoTrack is connected
    LaunchedEffect(videoTrack) {
        if (videoTrack != null && recorderRef.value == null) {
            val recorder = WebRtcVideoRecorder(videoFile) { savedFile ->
                Log.d("VideoFeedScreen", "Video recorded -> ${savedFile.absolutePath}")
            }
            recorderRef.value = recorder
            recorder.startRecording(videoTrack)
            isRecordingActive = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorderRef.value?.let { recorder ->
                if (recorder.isRecordingActive()) {
                    recorder.stopRecording()
                    Toast.makeText(context, "Video feed saved to Session History", Toast.LENGTH_SHORT).show()
                }
            }
            recorderRef.value = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding()
    ) {
        // Video Stream Surface View
        if (videoTrack != null) {
            val surfaceViewRenderer = remember {
                SurfaceViewRenderer(context).apply {
                    init(WebRTCManager.eglBase.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setEnableHardwareScaler(true)
                }
            }

            DisposableEffect(videoTrack, surfaceViewRenderer) {
                surfaceViewRef = surfaceViewRenderer
                try {
                    videoTrack.addSink(surfaceViewRenderer)
                } catch (e: Exception) {
                    Log.e("VideoFeedScreen", "Error adding sink to VideoTrack: ${e.message}")
                }

                onDispose {
                    surfaceViewRef = null
                    try {
                        videoTrack.removeSink(surfaceViewRenderer)
                        surfaceViewRenderer.release()
                    } catch (e: Exception) {
                        Log.e("VideoFeedScreen", "Error releasing surfaceViewRenderer: ${e.message}")
                    }
                }
            }

            AndroidView(
                factory = { surfaceViewRenderer },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CONNECTING TO LIVE VIDEO STREAM...",
                    color = LightGrey,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Black.copy(alpha = 0.7f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PureWhite)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecordingActive) {
                    val mins = recordingDurationSec / 60
                    val secs = recordingDurationSec % 60
                    val durText = String.format("%02d:%02d", mins, secs)

                    Surface(
                        color = Black.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(DangerRed, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "REC $durText",
                                color = PureWhite,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Surface(
                    color = DangerRed,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(PureWhite, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LIVE: ${displayName.ifBlank { "USER" }}",
                            color = PureWhite,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Bottom Action Controls Overlay
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch Camera Button
                Button(
                    onClick = onSwitchCamera,
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CAM", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                // Capture Snapshot Button
                Button(
                    onClick = {
                        val surface = surfaceViewRef
                        if (!isCapturing && surface != null && surface.width > 0 && surface.height > 0) {
                            isCapturing = true
                            captureSurfaceFrame(context, surface) {
                                isCapturing = false
                            }
                        }
                    },
                    enabled = !isCapturing && surfaceViewRef != null,
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isCapturing) "..." else "PHOTO", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                // Toggle Record Button
                Button(
                    onClick = {
                        if (isRecordingActive) {
                            recorderRef.value?.stopRecording()
                            isRecordingActive = false
                            Toast.makeText(context, "Video recording stopped & saved", Toast.LENGTH_SHORT).show()
                        } else if (videoTrack != null) {
                            val recorder = WebRtcVideoRecorder(videoFile) { savedFile ->
                                Log.d("VideoFeedScreen", "Video recorded: ${savedFile.absolutePath}")
                            }
                            recorderRef.value = recorder
                            recorder.startRecording(videoTrack)
                            isRecordingActive = true
                            Toast.makeText(context, "Video recording started", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecordingActive) DangerRed else PureWhite,
                        contentColor = if (isRecordingActive) PureWhite else Black
                    ),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isRecordingActive) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isRecordingActive) "STOP" else "RECORD",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/**
 * Captures a crisp, high-resolution evidence snapshot directly from the SurfaceViewRenderer using PixelCopy
 * and saves it asynchronously to MediaStore (Pictures/SoSafe).
 */
private fun captureSurfaceFrame(
    context: android.content.Context,
    surfaceView: SurfaceViewRenderer,
    onComplete: () -> Unit
) {
    try {
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) {
            onComplete()
            return
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                CoroutineScope(Dispatchers.IO).launch {
                    var saved = false
                    try {
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val filename = "SoSafe_Evidence_$timeStamp.jpg"

                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SoSafe")
                            }
                        }

                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            }
                            saved = true
                        }
                    } catch (e: Exception) {
                        Log.e("VideoFeedScreen", "Failed to write bitmap: ${e.message}")
                    }

                    withContext(Dispatchers.Main) {
                        if (saved) {
                            Toast.makeText(context, "📸 Evidence snapshot saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to save snapshot", Toast.LENGTH_SHORT).show()
                        }
                        onComplete()
                    }
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Snapshot capture failed", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            }
        }, Handler(Looper.getMainLooper()))
    } catch (e: Exception) {
        Log.e("VideoFeedScreen", "Capture frame exception: ${e.message}")
        onComplete()
    }
}
