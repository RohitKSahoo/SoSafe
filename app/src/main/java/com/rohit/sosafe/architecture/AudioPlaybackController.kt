package com.rohit.sosafe.architecture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.rohit.sosafe.data.contracts.AudioChunk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Deterministic Audio Player.
 * Only plays when SessionState is ACTIVE.
 * Routed to USAGE_MEDIA for dual-speaker output.
 */
class AudioPlaybackController(
    private val context: Context,
    private val sessionState: StateFlow<SessionState>
) {
    private val TAG = "AudioPlayback"
    private val mediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
    }
    private val queue = mutableListOf<AudioChunk>()
    private var isCurrentlyPlaying = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            sessionState.collectLatest { state ->
                when (state) {
                    is SessionState.ACTIVE -> {
                        // Ready to play
                    }
                    else -> {
                        stopAndClear()
                    }
                }
            }
        }
    }

    fun enqueue(chunk: AudioChunk) {
        if (sessionState.value !is SessionState.ACTIVE) return
        
        queue.add(chunk)
        if (!isCurrentlyPlaying) {
            playNext()
        }
    }

    private fun playNext() {
        if (queue.isEmpty() || sessionState.value !is SessionState.ACTIVE) {
            isCurrentlyPlaying = false
            return
        }

        val next = queue.removeAt(0)
        isCurrentlyPlaying = true

        try {
            mediaPlayer.reset()
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mediaPlayer.setDataSource(next.fileUrl)
            mediaPlayer.prepareAsync()
            mediaPlayer.setOnPreparedListener { it.start() }
            mediaPlayer.setOnCompletionListener { playNext() }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                isCurrentlyPlaying = false
                playNext()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}")
            isCurrentlyPlaying = false
            playNext()
        }
    }

    private fun stopAndClear() {
        Log.d(TAG, "Stopping playback and clearing queue.")
        queue.clear()
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
        }
        isCurrentlyPlaying = false
    }

    fun release() {
        stopAndClear()
        mediaPlayer.release()
        scope.cancel()
    }
}
