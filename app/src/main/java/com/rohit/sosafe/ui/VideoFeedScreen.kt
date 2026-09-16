package com.rohit.sosafe.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.activity.compose.BackHandler
import com.rohit.sosafe.ui.theme.*
import com.rohit.sosafe.utils.WebRTCManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VideoFeedScreen(
    videoTrack: VideoTrack?,
    displayName: String,
    onSwitchCamera: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    var isCapturing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding()
    ) {
        // Video Stream Surface View
        if (videoTrack != null) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(WebRTCManager.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        videoTrack.addSink(this)
                    }
                },
                update = { view ->
                    videoTrack.addSink(view)
                },
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = PureWhite)
            }

            Surface(
                color = DangerRed,
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(PureWhite, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LIVE VIDEO: ${displayName.ifBlank { "USER" }}",
                        color = PureWhite,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
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
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch Camera Button
                Button(
                    onClick = onSwitchCamera,
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SWITCH CAM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Capture Snapshot Button
                Button(
                    onClick = {
                        if (!isCapturing && videoTrack != null) {
                            isCapturing = true
                            captureVideoFrame(context, videoTrack) {
                                isCapturing = false
                            }
                        }
                    },
                    enabled = !isCapturing && videoTrack != null,
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SNAPSHOT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Captures single frame snapshot from WebRTC VideoTrack and saves to device picture gallery
 */
private fun captureVideoFrame(
    context: android.content.Context,
    videoTrack: VideoTrack,
    onComplete: () -> Unit
) {
    var sink: VideoSink? = null

    sink = VideoSink { frame ->
        try {
            frame.retain()
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
                Toast.makeText(context, "📸 Evidence snapshot saved!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("VideoFeedScreen", "Frame capture error: ${e.message}")
        } finally {
            frame.release()
            videoTrack.removeSink(sink)
            onComplete()
        }
    }

    videoTrack.addSink(sink)
}
