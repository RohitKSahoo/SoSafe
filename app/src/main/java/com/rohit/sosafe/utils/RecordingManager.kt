package com.rohit.sosafe.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

data class RecordingInfo(
    val sessionId: String,
    val file: File,
    val timestamp: Long,
    val durationText: String,
    val lastLocation: GeoPoint? = null
)

class RecordingManager(private val context: Context) {
    private val TAG = "RecordingManager"
    private val AUDIT_TAG = "SOS_AUDIT"
    private val client = OkHttpClient()

    fun getSessionFolder(userId: String, sessionId: String): File {
        val folder = File(context.getExternalFilesDir(null), "recordings/$userId/$sessionId")
        if (!folder.exists()) {
            val created = folder.mkdirs()
            Log.d(AUDIT_TAG, "FOLDER_CREATED: ${folder.absolutePath}")
        }
        return folder
    }

    fun saveMetadata(userId: String, sessionId: String, lat: Double, lng: Double, senderName: String = "") {
        try {
            val folder = getSessionFolder(userId, sessionId)
            val metadataFile = File(folder, "metadata.json")
            val json = JSONObject().apply {
                put("lat", lat)
                put("lng", lng)
                put("senderName", senderName)
                put("timestamp", System.currentTimeMillis())
            }
            metadataFile.writeText(json.toString())
            Log.d(AUDIT_TAG, "METADATA_SAVED: $sessionId at ($lat, $lng)")
        } catch (e: Exception) {
            Log.e(AUDIT_TAG, "METADATA_SAVE_ERROR: ${e.message}")
        }
    }

    fun getMetadata(userId: String, sessionId: String): RecordingInfo? {
        try {
            val folder = getSessionFolder(userId, sessionId)
            val metadataFile = File(folder, "metadata.json")
            val recordingFile = File(folder, "SOS_Recording.m4a")
            
            if (!metadataFile.exists() || !recordingFile.exists()) return null
            
            val json = JSONObject(metadataFile.readText())
            val lat = json.getDouble("lat")
            val lng = json.getDouble("lng")
            val timestamp = json.optLong("timestamp", recordingFile.lastModified())
            
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            
            return RecordingInfo(
                sessionId = sessionId,
                file = recordingFile,
                timestamp = timestamp,
                durationText = sdf.format(Date(timestamp)),
                lastLocation = GeoPoint(lat, lng)
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun saveChunk(userId: String, sessionId: String, sequence: Int, sourceFile: File) {
        if (!sourceFile.exists()) {
            Log.e(AUDIT_TAG, "CHUNK_SAVE_FAILED: Source missing for seq $sequence")
            return
        }
        try {
            val destFile = File(getSessionFolder(userId, sessionId), "chunk_$sequence.aac")
            sourceFile.copyTo(destFile, overwrite = true)
            Log.d(AUDIT_TAG, "CHUNK_SAVED: Seq $sequence for $sessionId saved locally.")
        } catch (e: Exception) {
            Log.e(AUDIT_TAG, "CHUNK_SAVE_ERROR: ${e.message}")
        }
    }

    /**
     * Downloads a chunk from Cloudinary and saves it locally.
     */
    suspend fun downloadAndSaveChunk(userId: String, sessionId: String, sequence: Int, url: String) {
        withContext(Dispatchers.IO) {
            val destFile = File(getSessionFolder(userId, sessionId), "chunk_$sequence.aac")
            if (destFile.exists()) return@withContext

            try {
                Log.d(AUDIT_TAG, "CHUNK_DOWNLOAD_START: Seq $sequence from $url")
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(AUDIT_TAG, "CHUNK_DOWNLOAD_FAILED: Seq $sequence, Code ${response.code}")
                        return@withContext
                    }
                    
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(AUDIT_TAG, "CHUNK_DOWNLOAD_SUCCESS: Seq $sequence for $sessionId")
                }
            } catch (e: Exception) {
                Log.e(AUDIT_TAG, "CHUNK_DOWNLOAD_ERROR: ${e.message}")
            }
        }
    }

    fun finalizeRecording(userId: String, sessionId: String): File? {
        Log.d(AUDIT_TAG, "FINALIZE_START: Processing session $sessionId")
        val folder = getSessionFolder(userId, sessionId)
        val chunks = folder.listFiles { _, name -> name.startsWith("chunk_") && name.endsWith(".aac") }
            ?.sortedBy { it.name.substringAfter("_").substringBefore(".").toIntOrNull() ?: 0 }
            ?: return null

        if (chunks.isEmpty()) {
            Log.w(AUDIT_TAG, "FINALIZE_SKIPPED: No chunks found for $sessionId")
            return null
        }

        val outputFile = File(folder, "SOS_Recording.m4a")
        if (outputFile.exists() && outputFile.length() > 0) {
            Log.d(AUDIT_TAG, "FINALIZE_SKIPPED: Final recording already exists.")
            return outputFile
        }

        var muxer: MediaMuxer? = null
        
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var totalOffsetUs = 0L

            for (chunk in chunks) {
                if (chunk.length() == 0L) continue
                
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(chunk.absolutePath)
                    
                    val trackIndex = selectAudioTrack(extractor)
                    if (trackIndex < 0) {
                        extractor.release()
                        continue
                    }
                    
                    extractor.selectTrack(trackIndex)
                    val format = extractor.getTrackFormat(trackIndex)

                    if (audioTrackIndex < 0) {
                        audioTrackIndex = muxer.addTrack(format)
                        muxer.start()
                    }

                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    val bufferInfo = MediaCodec.BufferInfo()
                    var lastSampleTimeUs = 0L

                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.flags = extractor.sampleFlags
                        bufferInfo.presentationTimeUs = totalOffsetUs + extractor.sampleTime
                        
                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                        lastSampleTimeUs = extractor.sampleTime
                        extractor.advance()
                    }
                    
                    val chunkDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        lastSampleTimeUs + 20000L
                    }
                    
                    totalOffsetUs += chunkDuration
                    extractor.release()
                } catch (e: Exception) {
                    Log.e(AUDIT_TAG, "FINALIZE_CHUNK_ERROR: ${chunk.name} -> ${e.message}")
                    try { extractor.release() } catch (ex: Exception) {}
                }
            }
            
            if (audioTrackIndex >= 0) {
                muxer.stop()
                Log.d(AUDIT_TAG, "FINALIZE_SUCCESS: $sessionId merged into ${outputFile.name}")
                // Clean up chunks after successful merge
                chunks.forEach { it.delete() }
                return outputFile
            } else {
                Log.e(AUDIT_TAG, "FINALIZE_FAILED: No valid audio tracks found.")
                return null
            }
        } catch (e: Exception) {
            Log.e(AUDIT_TAG, "FINALIZE_ERROR: ${e.message}")
            return null
        } finally {
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    fun getRecordingsForUser(userId: String): List<RecordingInfo> {
        val userFolder = File(context.getExternalFilesDir(null), "recordings/$userId")
        if (!userFolder.exists()) return emptyList()

        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        return userFolder.listFiles { file -> file.isDirectory }
            ?.mapNotNull { sessionDir ->
                val sessionId = sessionDir.name
                val metadata = getMetadata(userId, sessionId)
                if (metadata != null) {
                    metadata
                } else {
                    val recordingFile = File(sessionDir, "SOS_Recording.m4a")
                    if (recordingFile.exists() && recordingFile.length() > 0) {
                        val timestamp = sessionId.substringAfter("session_").toLongOrNull() ?: sessionDir.lastModified()
                        RecordingInfo(
                            sessionId = sessionId,
                            file = recordingFile,
                            timestamp = timestamp,
                            durationText = sdf.format(Date(timestamp))
                        )
                    } else null
                }
            }?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }
}
