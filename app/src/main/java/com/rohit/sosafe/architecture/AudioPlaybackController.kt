package com.rohit.sosafe.architecture

import android.content.Context
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
 */
class AudioPlaybackController(
    private val context: Context,
    private val sessionState: StateFlow<SessionState>
) {
    private val TAG = "AudioPlayback"
    private val mediaPlayer = MediaPlayer()
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
