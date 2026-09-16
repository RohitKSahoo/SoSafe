package com.rohit.sosafe.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.rohit.sosafe.data.supabase.SupabaseApi
import com.rohit.sosafe.data.supabase.SupabaseRealtimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.*

class WebRTCManager(
    private val context: Context,
    private val sessionId: String,
    private val onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit,
    private val onAudioTrackReceived: (AudioTrack) -> Unit = {},
    private val onVideoTrackReceived: (VideoTrack) -> Unit = {},
    private val isReceiver: Boolean = false
) {
    private val TAG = "WebRTC_MANAGER"
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val processedCandidates = mutableSetOf<String>()
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var sessionRealtime: SupabaseRealtimeClient? = null
    private var isStopped = false
    private var lastHandledOffer = ""
    private var lastHandledReconnectRequest = ""
    private var lastHandledCameraCommand = ""

    val rootEglBase: EglBase by lazy { eglBase }

    companion object {
        val eglBase: EglBase by lazy { EglBase.create() }
    }

    private val iceServers = listOf(
        // STUN Servers (Direct P2P)
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:openrelay.metered.ca:80").createIceServer(),

        // TURN Relay Servers (Bypasses Carrier NAT / 4G / 5G / CGNAT restrictions)
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    private fun initializeFactory() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        
        val audioDeviceModuleBuilder = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(!isReceiver) 
            .setUseHardwareNoiseSuppressor(true)
            .setAudioSource(if (isReceiver) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_COMMUNICATION)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(if (isReceiver) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(if (isReceiver) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        
        audioDeviceModuleBuilder.setAudioAttributes(audioAttributes)

        val audioDeviceModule = audioDeviceModuleBuilder
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(p0: String?) { Log.e(TAG, "AudioRecord Init Error: $p0") }
                override fun onWebRtcAudioRecordStartError(p0: JavaAudioDeviceModule.AudioRecordStartErrorCode?, p1: String?) { Log.e(TAG, "AudioRecord Start Error: $p1") }
                override fun onWebRtcAudioRecordError(p0: String?) { Log.e(TAG, "AudioRecord Error: $p0") }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(p0: String?) { Log.e(TAG, "AudioTrack Init Error: $p0") }
                override fun onWebRtcAudioTrackStartError(p0: JavaAudioDeviceModule.AudioTrackStartErrorCode?, p1: String?) { Log.e(TAG, "AudioTrack Start Error: $p1") }
                override fun onWebRtcAudioTrackError(p0: String?) { Log.e(TAG, "AudioTrack Error: $p0") }
            })
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        
        audioDeviceModule.release()
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Try back camera first, fallback to front camera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) return capturer
            }
        }

        return null
    }

    private var isFrontFacingSelected = false

    fun switchCamera() {
        if (isReceiver) {
            // Guardian device sending remote command to Sender via webrtc_answer
            scope.launch(Dispatchers.IO) {
                try {
                    val commandObj = JSONObject().apply {
                        put("webrtc_answer", "CMD_SWITCH_CAMERA_${System.currentTimeMillis()}")
                    }
                    SupabaseApi.update("sessions", "session_id=eq.$sessionId", commandObj)
                    Log.d(TAG, "Sent remote camera switch command for session $sessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send camera switch command: ${e.message}")
                }
            }
        } else {
            // Sender device executing camera hardware switch
            val cameraCapturer = videoCapturer as? CameraVideoCapturer
            if (cameraCapturer != null) {
                val enumerator = Camera2Enumerator(context)
                val deviceNames = enumerator.deviceNames
                
                // Find target camera device name (toggle between front and back)
                val targetDevice = deviceNames.firstOrNull { name ->
                    if (isFrontFacingSelected) enumerator.isBackFacing(name) else enumerator.isFrontFacing(name)
                } ?: deviceNames.firstOrNull()

                if (targetDevice != null) {
                    cameraCapturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                        override fun onCameraSwitchDone(isFront: Boolean) {
                            isFrontFacingSelected = isFront
                            Log.d(TAG, "Camera switched successfully to device: $targetDevice (Front: $isFront)")
                        }

                        override fun onCameraSwitchError(errorDescription: String?) {
                            Log.e(TAG, "Explicit camera switch failed ($errorDescription). Retrying default switch...")
                            cameraCapturer.switchCamera(null)
                        }
                    }, targetDevice)
                } else {
                    cameraCapturer.switchCamera(null)
                }
            }
        }
    }

    fun startSender() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start WebRTC Sender: RECORD_AUDIO permission not granted")
            return
        }

        Log.d(TAG, "Starting WebRTC Sender for session: $sessionId")
        initializeFactory()

        if (audioSource == null) {
            audioSource = factory?.createAudioSource(MediaConstraints())
            audioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)
        }

        // Initialize Camera Video Track if permission is granted
        if (videoCapturer == null && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                videoCapturer = createCameraCapturer()
                if (videoCapturer != null) {
                    videoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
                    videoCapturer?.initialize(SurfaceTextureHelper.create("WebRTC_CameraThread", rootEglBase.eglBaseContext), context, videoSource?.capturerObserver)
                    videoCapturer?.startCapture(1280, 720, 30)

                    videoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
                    Log.d(TAG, "WebRTC Video Track created and started capture")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebRTC Video Track: ${e.message}")
            }
        }

        recreateSenderPeerConnection()
        listenForAnswer()
    }

    private fun recreateSenderPeerConnection() {
        Log.d(TAG, "Recreating Sender PeerConnection for fresh offer...")
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing old PeerConnection: ${e.message}")
        }
        peerConnection = null

        synchronized(this) {
            processedCandidates.clear()
            pendingIceCandidates.clear()
        }

        createPeerConnection()

        audioTrack?.let { peerConnection?.addTrack(it) }
        videoTrack?.let { peerConnection?.addTrack(it) }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        scope.launch(Dispatchers.IO) {
                            val json = JSONObject().apply {
                                put("webrtc_offer", sdp.description)
                                put("webrtc_answer", "")
                                put("ice_candidates", JSONArray())
                            }
                            SupabaseApi.update("sessions", "session_id=eq.$sessionId", json)
                            Log.d(TAG, "Fresh WebRTC Offer published to Supabase for session $sessionId")
                        }
                    }
                }, sdp)
            }
        }, constraints)
    }

    fun startReceiver() {
        Log.d(TAG, "Starting WebRTC Receiver for session: $sessionId")
        initializeFactory()
        createPeerConnection()
        listenForOffer()

        // Send reconnect request to Sender so Sender produces a fresh offer and resets signaling state
        scope.launch(Dispatchers.IO) {
            try {
                val reqJson = JSONObject().apply {
                    put("webrtc_answer", "RECONNECT_${System.currentTimeMillis()}")
                }
                SupabaseApi.update("sessions", "session_id=eq.$sessionId", reqJson)
                Log.d(TAG, "Sent WebRTC RECONNECT request for session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending WebRTC RECONNECT request: ${e.message}")
            }
        }
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        
        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch(Dispatchers.IO) {
                    synchronized(this@WebRTCManager) {
                        try {
                            val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                            if (rows.length() > 0) {
                                val sessionObj = rows.getJSONObject(0)
                                val candidatesArr = sessionObj.optJSONArray("ice_candidates") ?: JSONArray()
                                
                                val candidateSdp = candidate.sdp
                                var exists = false
                                for (i in 0 until candidatesArr.length()) {
                                    if (candidatesArr.getJSONObject(i).optString("candidate") == candidateSdp) {
                                        exists = true
                                        break
                                    }
                                }
                                
                                if (!exists) {
                                    val candidateObj = JSONObject().apply {
                                        put("sdpMid", candidate.sdpMid)
                                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                                        put("candidate", candidate.sdp)
                                        put("isReceiver", isReceiver)
                                    }
                                    candidatesArr.put(candidateObj)

                                    val updateObj = JSONObject().apply { put("ice_candidates", candidatesArr) }
                                    SupabaseApi.update("sessions", "session_id=eq.$sessionId", updateObj)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving ICE candidate: ${e.message}")
                        }
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection state changed: $newState")
                onConnectionStateChange(newState)
            }

            override fun onIceConnectionChange(iceState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection state changed: $iceState")
                when (iceState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        onConnectionStateChange(PeerConnection.PeerConnectionState.CONNECTED)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        onConnectionStateChange(PeerConnection.PeerConnectionState.FAILED)
                    }
                    else -> {}
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is AudioTrack) {
                    onAudioTrackReceived(track)
                } else if (track is VideoTrack) {
                    onVideoTrackReceived(track)
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
                val track = p0?.track()
                if (track is AudioTrack) {
                    onAudioTrackReceived(track)
                } else if (track is VideoTrack) {
                    onVideoTrackReceived(track)
                }
            }
        })
        
        listenForIceCandidates()
    }

    private val sessionRecordListeners = mutableListOf<(JSONObject) -> Unit>()

    private fun listenForOffer() {
        registerSessionListener { record ->
            val offer = record.optString("webrtc_offer")
            val answer = record.optString("webrtc_answer")
            if (offer.isNotBlank() && offer != lastHandledOffer && !answer.startsWith("RECONNECT_")) {
                if (peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
                    lastHandledOffer = offer
                    Log.d(TAG, "Receiver applying fresh WebRTC offer from sender")
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, offer)
                    peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            drainPendingIceCandidates()
                            createAnswer()
                        }
                    }, sdp)
                }
            }
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        scope.launch(Dispatchers.IO) {
                            val json = JSONObject().apply { put("webrtc_answer", sdp.description) }
                            SupabaseApi.update("sessions", "session_id=eq.$sessionId", json)
                            Log.d(TAG, "Receiver WebRTC answer published to Supabase for session $sessionId")
                        }
                    }
                }, sdp)
            }
        }, constraints)
    }

    private fun listenForAnswer() {
        registerSessionListener { record ->
            val answer = record.optString("webrtc_answer")
            if (answer.startsWith("RECONNECT_")) {
                if (answer != lastHandledReconnectRequest) {
                    lastHandledReconnectRequest = answer
                    Log.d(TAG, "Received reconnection request: $answer")
                    recreateSenderPeerConnection()
                }
            } else if (answer.startsWith("CMD_SWITCH_CAMERA_")) {
                if (answer != lastHandledCameraCommand) {
                    lastHandledCameraCommand = answer
                    Log.d(TAG, "Received camera switch command: $answer")
                    switchCamera()
                }
            } else if (answer.isNotBlank() && peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                Log.d(TAG, "Received WebRTC answer, applying to PeerConnection")
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, answer)
                peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "WebRTC Answer applied successfully on Sender")
                        drainPendingIceCandidates()
                    }
                }, sdp)
            }
        }
    }

    private fun drainPendingIceCandidates() {
        synchronized(pendingIceCandidates) {
            for (candidate in pendingIceCandidates) {
                try {
                    peerConnection?.addIceCandidate(candidate)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding queued ICE candidate: ${e.message}")
                }
            }
            pendingIceCandidates.clear()
        }
    }

    private fun listenForIceCandidates() {
        registerSessionListener { record ->
            val iceArr = record.optJSONArray("ice_candidates") ?: JSONArray()
            for (i in 0 until iceArr.length()) {
                val data = iceArr.getJSONObject(i)
                val sdp = data.optString("candidate")
                if (data.has("isReceiver") && data.optBoolean("isReceiver", false) == isReceiver) {
                    continue // Skip candidates generated by self
                }
                if (sdp.isNotBlank() && !processedCandidates.contains(sdp)) {
                    val candidate = IceCandidate(
                        data.optString("sdpMid"),
                        data.optInt("sdpMLineIndex"),
                        sdp
                    )
                    processedCandidates.add(sdp)
                    synchronized(pendingIceCandidates) {
                        if (peerConnection?.remoteDescription != null) {
                            try {
                                peerConnection?.addIceCandidate(candidate)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error adding ICE candidate: ${e.message}")
                            }
                        } else {
                            pendingIceCandidates.add(candidate)
                        }
                    }
                }
            }
        }
    }

    private fun registerSessionListener(onRecord: (JSONObject) -> Unit) {
        synchronized(sessionRecordListeners) {
            sessionRecordListeners.add(onRecord)
        }
        
        if (sessionRealtime == null) {
            scope.launch(Dispatchers.IO) {
                while (!isStopped) {
                    try {
                        val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                        if (rows.length() > 0) {
                            val record = rows.getJSONObject(0)
                            synchronized(sessionRecordListeners) {
                                sessionRecordListeners.forEach { listener ->
                                    try { listener(record) } catch (e: Exception) {}
                                }
                            }
                        }
                    } catch (e: Exception) {}
                    kotlinx.coroutines.delay(500)
                }
            }

            sessionRealtime = SupabaseRealtimeClient("sessions", "session_id", sessionId) { _, record ->
                synchronized(sessionRecordListeners) {
                    sessionRecordListeners.forEach { listener ->
                        try { listener(record) } catch (e: Exception) {}
                    }
                }
            }.apply { start() }
        }
    }

    fun stop() {
        isStopped = true
        val pc = peerConnection
        val fact = factory
        val capturer = videoCapturer
        val vSource = videoSource
        val vTrack = videoTrack
        val aSource = audioSource
        val aTrack = audioTrack
        val sRealtime = sessionRealtime

        videoCapturer = null
        videoSource = null
        videoTrack = null
        audioSource = null
        audioTrack = null
        sessionRealtime = null
        peerConnection = null
        factory = null

        scope.launch(Dispatchers.IO) {
            try {
                capturer?.stopCapture()
                capturer?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping video capturer: ${e.message}")
            }

            try { aTrack?.dispose() } catch (e: Exception) {}
            try { aSource?.dispose() } catch (e: Exception) {}
            try { vSource?.dispose() } catch (e: Exception) {}
            try { vTrack?.dispose() } catch (e: Exception) {}
            try { sRealtime?.stop() } catch (e: Exception) {}
            try { pc?.close() } catch (e: Exception) {}
            try { pc?.dispose() } catch (e: Exception) {}
            try { fact?.dispose() } catch (e: Exception) {}
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.e("WebRTC", "SDP Create Failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e("WebRTC", "SDP Set Failure: $p0") }
    }
}
