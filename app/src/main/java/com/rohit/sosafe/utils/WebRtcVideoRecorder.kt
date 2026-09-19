package com.rohit.sosafe.utils

import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.YuvHelper
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes an incoming WebRTC VideoTrack into an MP4 file (H.264 / AVC) using
 * Android's hardware MediaCodec and MediaMuxer.
 */
class WebRtcVideoRecorder(
    private val outputFile: File,
    private val onRecordingFinished: ((File) -> Unit)? = null
) : VideoSink {

    private val TAG = "WebRtcVideoRecorder"
    private val isRecording = AtomicBoolean(false)
    private var isEncoderInitialized = false
    private var isMuxerStarted = false

    private var videoTrack: VideoTrack? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1

    private var targetWidth = 720
    private var targetHeight = 1280
    private val bitRate = 2_000_000 // 2 Mbps
    private val frameRate = 30
    private val iFrameInterval = 1 // 1 keyframe per second

    private var handlerThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private var startTimestampNs = 0L
    private var frameCount = 0L
    private var selectedColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    private val bufferInfo = MediaCodec.BufferInfo()

    fun isRecordingActive(): Boolean = isRecording.get()

    /**
     * Attaches to the given VideoTrack and begins recording frames.
     */
    fun startRecording(track: VideoTrack) {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Recording already active.")
            return
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        this.videoTrack = track

        handlerThread = HandlerThread("WebRtcEncoderThread").apply { start() }
        encoderHandler = Handler(handlerThread!!.looper)

        startTimestampNs = 0L
        frameCount = 0L
        isEncoderInitialized = false
        isMuxerStarted = false
        videoTrackIndex = -1

        track.addSink(this)
        Log.d(TAG, "WebRTC Video recording started -> ${outputFile.absolutePath}")
    }

    override fun onFrame(frame: VideoFrame) {
        if (!isRecording.get()) return

        val width = (frame.rotatedWidth / 2) * 2
        val height = (frame.rotatedHeight / 2) * 2

        if (width <= 0 || height <= 0) return

        // Initialize encoder with initial frame resolution
        if (!isEncoderInitialized) {
            encoderHandler?.post {
                initEncoder(width, height)
            }
        }

        val i420 = frame.buffer.toI420() ?: return
        val rotation = frame.rotation
        val timestampNs = frame.timestampNs

        encoderHandler?.post {
            var rotatedBuffer: VideoFrame.I420Buffer? = null
            try {
                if (isRecording.get() && isEncoderInitialized) {
                    if (rotation != 0) {
                        val rotated = JavaI420Buffer.allocate(width, height)
                        YuvHelper.I420Rotate(
                            i420.dataY, i420.strideY,
                            i420.dataU, i420.strideU,
                            i420.dataV, i420.strideV,
                            rotated.dataY, rotated.strideY,
                            rotated.dataU, rotated.strideU,
                            rotated.dataV, rotated.strideV,
                            i420.width, i420.height,
                            rotation
                        )
                        rotatedBuffer = rotated
                        feedFrameToEncoder(rotated, timestampNs)
                    } else {
                        feedFrameToEncoder(i420, timestampNs)
                    }
                    drainEncoder(endOfStream = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error encoding frame: ${e.message}")
            } finally {
                i420.release()
                rotatedBuffer?.release()
            }
        }
    }

    private fun initEncoder(width: Int, height: Int) {
        if (isEncoderInitialized) return

        try {
            targetWidth = width
            targetHeight = height

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(0)
            }

            isEncoderInitialized = true
            Log.d(TAG, "MediaCodec encoder initialized: ${targetWidth}x${targetHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MediaCodec/MediaMuxer: ${e.message}")
            stopRecording()
        }
    }

    private fun feedFrameToEncoder(i420: VideoFrame.I420Buffer, timestampNs: Long) {
        val codec = mediaCodec ?: return

        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
        if (inputBufferIndex < 0) return

        val inputBuffer: ByteBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
        inputBuffer.clear()

        val width = i420.width
        val height = i420.height

        val yBuffer = i420.dataY
        val uBuffer = i420.dataU
        val vBuffer = i420.dataV
        val yStride = i420.strideY
        val uStride = i420.strideU
        val vStride = i420.strideV

        // 1. Copy Y Plane
        for (row in 0 until height) {
            yBuffer.position(row * yStride)
            val limit = row * yStride + width
            yBuffer.limit(limit)
            inputBuffer.put(yBuffer)
        }

        // 2. Interleave U and V into NV12 format
        val uvHeight = (height + 1) / 2
        val uvWidth = (width + 1) / 2
        val uvRow = ByteArray(width)

        for (row in 0 until uvHeight) {
            var colIndex = 0
            for (col in 0 until uvWidth) {
                uBuffer.position(row * uStride + col)
                vBuffer.position(row * vStride + col)
                uvRow[colIndex++] = uBuffer.get()
                uvRow[colIndex++] = vBuffer.get()
            }
            inputBuffer.put(uvRow, 0, colIndex)
        }

        if (startTimestampNs == 0L) {
            startTimestampNs = timestampNs
        }

        val presentationTimeUs = (timestampNs - startTimestampNs) / 1000L
        codec.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), presentationTimeUs, 0)
        frameCount++
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    Log.w(TAG, "format changed after muxer started")
                } else {
                    val newFormat = codec.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    isMuxerStarted = true
                    Log.d(TAG, "MediaMuxer started with video track index: $videoTrackIndex")
                }
            } else if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex) ?: continue

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size > 0 && isMuxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }

    /**
     * Gracefully stops recording, flushes the encoder, and finalizes the MP4 file.
     */
    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return

        try {
            videoTrack?.removeSink(this)
            videoTrack = null

            encoderHandler?.post {
                try {
                    // Send EOS to encoder
                    mediaCodec?.let { codec ->
                        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                        if (inputBufferIndex >= 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                        drainEncoder(endOfStream = true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during EOS flush: ${e.message}")
                } finally {
                    releaseResources()
                    if (outputFile.exists() && outputFile.length() > 0) {
                        Log.d(TAG, "WebRTC Video recording saved successfully (${outputFile.length()} bytes) -> ${outputFile.name}")
                        onRecordingFinished?.invoke(outputFile)
                    } else {
                        Log.w(TAG, "Recording finished but output file is empty or missing")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
            releaseResources()
        }
    }

    private fun releaseResources() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec: ${e.message}")
        } finally {
            mediaCodec = null
        }

        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaMuxer: ${e.message}")
        } finally {
            mediaMuxer = null
            isMuxerStarted = false
            isEncoderInitialized = false
        }

        handlerThread?.quitSafely()
        handlerThread = null
        encoderHandler = null
    }
}
