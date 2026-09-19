package com.rohit.sosafe.ui

import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.rohit.sosafe.utils.RecordingManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dedicated fullscreen / high-resolution player for archived WebRTC video recordings.
 */
@Composable
fun RecordedVideoPlayerScreen(
    videoFile: File,
    senderDisplayName: String,
    recordingManager: RecordingManager,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    BackHandler {
        onClose()
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try {
                if (isPrepared && videoViewRef != null) {
                    position = videoViewRef!!.currentPosition
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── TOP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(DarkGrey)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = PureWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "RECORDED VIDEO ARCHIVE",
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        if (senderDisplayName.isNotBlank()) {
                            Text(
                                text = senderDisplayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = LightGrey
                            )
                        }
                    }
                }

                val fileSizeMb = String.format(
                    java.util.Locale.getDefault(),
                    "%.1f MB",
                    videoFile.length() / (1024f * 1024f)
                )
                Text(
                    text = fileSizeMb,
                    color = LightGrey,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // ── VIDEO VIEWPORT ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(videoFile.absolutePath)
                            setOnPreparedListener { mp ->
                                duration = mp.duration
                                isPrepared = true
                                mp.isLooping = true
                            }
                            setOnCompletionListener {
                                isPlaying = false
                            }
                            videoViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (!isPrepared) {
                    CircularProgressIndicator(color = PureWhite, strokeWidth = 2.dp)
                }
            }

            // ── BOTTOM PLAYBACK & EXPORT CONTROLS ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkGrey)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Time Indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMs(position),
                        color = PureWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatMs(duration),
                        color = LightGrey,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Seek Slider
                Slider(
                    value = if (duration > 0) position.toFloat() else 0f,
                    onValueChange = { newPos ->
                        position = newPos.toInt()
                        videoViewRef?.seekTo(position)
                    },
                    valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = PureWhite,
                        activeTrackColor = PureWhite,
                        inactiveTrackColor = MediumGrey
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Action Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Playback Buttons: -10s, Play/Pause, +10s
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val newPos = Math.max(0, position - 10000)
                                position = newPos
                                videoViewRef?.seekTo(newPos)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(2.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite)
                        ) {
                            Text("-10s", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (isPlaying) {
                                    videoViewRef?.pause()
                                    isPlaying = false
                                } else {
                                    videoViewRef?.start()
                                    isPlaying = true
                                }
                            },
                            shape = RoundedCornerShape(2.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PureWhite,
                                contentColor = Black
                            ),
                            modifier = Modifier.size(42.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val newPos = Math.min(duration, position + 10000)
                                position = newPos
                                videoViewRef?.seekTo(newPos)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(2.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite)
                        ) {
                            Text("+10s", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Export Button
                    Button(
                        onClick = {
                            if (!isExporting) {
                                isExporting = true
                                scope.launch {
                                    val uri = recordingManager.exportVideoToGallery(videoFile)
                                    isExporting = false
                                    if (uri != null) {
                                        Toast.makeText(
                                            context,
                                            "Video saved to Gallery / Downloads!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Failed to export video",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkGrey,
                            contentColor = PureWhite
                        ),
                        shape = RoundedCornerShape(2.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey),
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = PureWhite,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = PureWhite
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "EXPORT VIDEO",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
