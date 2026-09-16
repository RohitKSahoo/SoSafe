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
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val processedCandidates = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var sessionRealtime: SupabaseRealtimeClient? = null

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
            // Guardian device sending remote command to Sender
            scope.launch(Dispatchers.IO) {
                try {
                    val commandObj = JSONObject().apply {
                        put("camera_command", "SWITCH_${System.currentTimeMillis()}")
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

    private var lastHandledCameraCommand: String = ""

    private fun listenForCameraCommands() {
        registerSessionListener { record ->
            val cmd = record.optString("camera_command")
            if (cmd.isNotBlank() && cmd != lastHandledCameraCommand) {
                lastHandledCameraCommand = cmd
                Log.d(TAG, "Received remote camera command: $cmd")
                switchCamera()
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
        createPeerConnection()
        
        val audioSource = factory?.createAudioSource(MediaConstraints())
        val audioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)
        peerConnection?.addTrack(audioTrack)

        // Initialize Camera Video Track if permission is granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                videoCapturer = createCameraCapturer()
                if (videoCapturer != null) {
                    videoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
                    videoCapturer?.initialize(SurfaceTextureHelper.create("WebRTC_CameraThread", rootEglBase.eglBaseContext), context, videoSource?.capturerObserver)
                    videoCapturer?.startCapture(1280, 720, 30)

                    videoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
                    peerConnection?.addTrack(videoTrack)
                    Log.d(TAG, "WebRTC Video Track created and added to PeerConnection")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebRTC Video Track: ${e.message}")
            }
        }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        scope.launch(Dispatchers.IO) {
                            val json = JSONObject().apply { put("webrtc_offer", sdp.description) }
                            SupabaseApi.update("sessions", "session_id=eq.$sessionId", json)
                        }
                    }
                }, sdp)
            }
        }, constraints)

        listenForAnswer()
        listenForCameraCommands()
    }

    fun startReceiver() {
        Log.d(TAG, "Starting WebRTC Receiver for session: $sessionId")
        initializeFactory()
        createPeerConnection()
        listenForOffer()
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
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
        
        listenForIceCandidates()
    }

    private val sessionRecordListeners = mutableListOf<(JSONObject) -> Unit>()

    private fun listenForOffer() {
        registerSessionListener { record ->
            val offer = record.optString("webrtc_offer")
            if (offer.isNotBlank() && peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, offer)
                peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        createAnswer()
                    }
                }, sdp)
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
                        }
                    }
                }, sdp)
            }
        }, constraints)

        listenForIceCandidates()
    }

    private fun listenForAnswer() {
        registerSessionListener { record ->
            val answer = record.optString("webrtc_answer")
            if (answer.isNotBlank() && peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, answer)
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
            }
        }
    }

    private fun listenForIceCandidates() {
        registerSessionListener { record ->
            if (peerConnection?.remoteDescription == null) {
                return@registerSessionListener
            }
            val iceArr = record.optJSONArray("ice_candidates") ?: JSONArray()
            for (i in 0 until iceArr.length()) {
                val data = iceArr.getJSONObject(i)
                val sdp = data.optString("candidate")
                if (sdp.isNotBlank() && !processedCandidates.contains(sdp)) {
                    val candidate = IceCandidate(
                        data.optString("sdpMid"),
                        data.optInt("sdpMLineIndex"),
                        sdp
                    )
                    val added = peerConnection?.addIceCandidate(candidate) ?: false
                    if (added) {
                        processedCandidates.add(sdp)
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
                while (peerConnection != null) {
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
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video capturer: ${e.message}")
        }
        videoCapturer = null

        try { videoSource?.dispose() } catch (e: Exception) {}
        videoSource = null

        try { videoTrack?.dispose() } catch (e: Exception) {}
        videoTrack = null

        try { sessionRealtime?.stop() } catch (e: Exception) {}
        sessionRealtime = null

        try { peerConnection?.close() } catch (e: Exception) {}
        try { peerConnection?.dispose() } catch (e: Exception) {}
        peerConnection = null

        try { factory?.dispose() } catch (e: Exception) {}
        factory = null
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.e("WebRTC", "SDP Create Failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e("WebRTC", "SDP Set Failure: $p0") }
    }
}
