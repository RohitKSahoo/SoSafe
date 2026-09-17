package com.rohit.sosafe.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.rohit.sosafe.data.UserManager
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
import java.util.concurrent.ConcurrentHashMap

class WebRTCManager(
    private val context: Context,
    private val sessionId: String,
    private val onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit,
    private val onAudioTrackReceived: (AudioTrack) -> Unit = {},
    private val onVideoTrackReceived: (VideoTrack) -> Unit = {},
    private val isReceiver: Boolean = false,
    guardianId: String = ""
) {
    private val TAG = "WebRTC_MANAGER"
    
    // Guardian ID resolution (ensures each guardian has an isolated signaling identity)
    val effectiveGuardianId: String = if (guardianId.isNotBlank()) {
        guardianId
    } else {
        UserManager(context).getUserCodeSync() ?: "guardian_${UUID.randomUUID().toString().take(6)}"
    }

    // Sender: 1-to-N Mesh connections (keyed by guardianId)
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val pendingIceCandidatesMap = ConcurrentHashMap<String, MutableList<IceCandidate>>()
    private val processedCandidatesMap = ConcurrentHashMap<String, MutableSet<String>>()
    private val lastHandledOffersMap = ConcurrentHashMap<String, String>()
    private val lastHandledAnswersMap = ConcurrentHashMap<String, String>()
    private val lastHandledRequestTimestamps = ConcurrentHashMap<String, Long>()

    // Receiver: Single 1-to-1 connection to Sender
    private var receiverPeerConnection: PeerConnection? = null
    private val receiverPendingCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())
    private val receiverProcessedCandidates = Collections.synchronizedSet(mutableSetOf<String>())
    private var lastHandledReceiverOffer = ""

    // Shared Hardware & Media Tracks (created once on Sender, shared across all connections)
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var sessionRealtime: SupabaseRealtimeClient? = null
    private var isStopped = false
    private var lastHandledCameraCommand = ""
    private var isFrontFacingSelected = false

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

    fun switchCamera() {
        if (isReceiver) {
            // Guardian device sending remote command to Sender via its partitioned answer slot
            scope.launch(Dispatchers.IO) {
                try {
                    val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                    val currentObj = if (rows.length() > 0) rows.getJSONObject(0) else JSONObject()
                    val answersMap = try {
                        val raw = currentObj.optString("webrtc_answer", "{}")
                        if (raw.trim().startsWith("{")) JSONObject(raw) else JSONObject()
                    } catch (e: Exception) {
                        JSONObject()
                    }

                    val myEntry = answersMap.optJSONObject(effectiveGuardianId) ?: JSONObject()
                    myEntry.put("cmd", "CMD_SWITCH_CAMERA_${System.currentTimeMillis()}")
                    answersMap.put(effectiveGuardianId, myEntry)

                    val commandObj = JSONObject().apply {
                        put("webrtc_answer", answersMap.toString())
                    }
                    SupabaseApi.update("sessions", "session_id=eq.$sessionId", commandObj)
                    Log.d(TAG, "Sent remote camera switch command from guardian $effectiveGuardianId for session $sessionId")
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

    // ==========================================
    // SENDER IMPLEMENTATION (1-to-N WebRTC MESH)
    // ==========================================

    fun startSender() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start WebRTC Sender: RECORD_AUDIO permission not granted")
            return
        }

        Log.d(TAG, "Starting WebRTC Sender (Mesh mode) for session: $sessionId")
        initializeFactory()

        if (audioSource == null) {
            audioSource = factory?.createAudioSource(MediaConstraints())
            audioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)
        }

        // Initialize Camera Video Track once (Shared across all guardian connections)
        if (videoCapturer == null && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                videoCapturer = createCameraCapturer()
                if (videoCapturer != null) {
                    videoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
                    videoCapturer?.initialize(SurfaceTextureHelper.create("WebRTC_CameraThread", rootEglBase.eglBaseContext), context, videoSource?.capturerObserver)
                    videoCapturer?.startCapture(640, 480, 24) // Optimized for multi-guardian mobile uplink

                    videoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
                    Log.d(TAG, "WebRTC Video Track created and started capture (640x480@24fps)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebRTC Video Track: ${e.message}")
            }
        }

        // Initialize default/legacy offer for immediate backwards compatibility
        createSenderPeerConnectionForGuardian("default")
        listenForAnswer()
        listenForIceCandidates()
    }

    private fun createSenderPeerConnectionForGuardian(guardianKey: String) {
        Log.d(TAG, "Creating dedicated Sender PeerConnection for Guardian: $guardianKey")
        try {
            peerConnections[guardianKey]?.close()
            peerConnections[guardianKey]?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing old PeerConnection for $guardianKey: ${e.message}")
        }
        peerConnections.remove(guardianKey)

        pendingIceCandidatesMap[guardianKey] = Collections.synchronizedList(mutableListOf())
        processedCandidatesMap[guardianKey] = Collections.synchronizedSet(mutableSetOf())

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch(Dispatchers.IO) {
                    saveSenderIceCandidate(guardianKey, candidate)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Guardian [$guardianKey] Connection State: $newState")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    onConnectionStateChange(newState)
                } else if (newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.CLOSED) {
                    Log.w(TAG, "Guardian [$guardianKey] disconnected/failed. Cleaning up peer connection.")
                    peerConnections.remove(guardianKey)
                }
            }

            override fun onIceConnectionChange(iceState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "Guardian [$guardianKey] ICE State: $iceState")
                if (iceState == PeerConnection.IceConnectionState.CONNECTED || iceState == PeerConnection.IceConnectionState.COMPLETED) {
                    onConnectionStateChange(PeerConnection.PeerConnectionState.CONNECTED)
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
            override fun onTrack(p0: RtpTransceiver?) {}
        }) ?: return

        peerConnections[guardianKey] = pc

        // Attach shared audio and video tracks to this guardian's PeerConnection
        audioTrack?.let { pc.addTrack(it) }
        videoTrack?.let { pc.addTrack(it) }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        scope.launch(Dispatchers.IO) {
                            publishSenderOffer(guardianKey, sdp.description)
                        }
                    }
                }, sdp)
            }
        }, constraints)
    }

    private fun publishSenderOffer(guardianKey: String, offerSdp: String) {
        try {
            val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
            val currentObj = if (rows.length() > 0) rows.getJSONObject(0) else JSONObject()
            
            val offersMap = try {
                val raw = currentObj.optString("webrtc_offer", "{}")
                if (raw.trim().startsWith("{")) JSONObject(raw) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }

            val entry = JSONObject().apply {
                put("offer", offerSdp)
                put("timestamp", System.currentTimeMillis())
            }
            offersMap.put(guardianKey, entry)

            val updateJson = JSONObject().apply {
                put("webrtc_offer", offersMap.toString())
                if (guardianKey == "default") {
                    // Retain top-level offer fallback for legacy guardians
                    put("webrtc_answer", "")
                }
            }
            SupabaseApi.update("sessions", "session_id=eq.$sessionId", updateJson)
            Log.d(TAG, "Published WebRTC Offer for guardian [$guardianKey] on session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing offer for [$guardianKey]: ${e.message}")
        }
    }

    private fun saveSenderIceCandidate(guardianKey: String, candidate: IceCandidate) {
        synchronized(this) {
            try {
                val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                if (rows.length() > 0) {
                    val sessionObj = rows.getJSONObject(0)
                    val candidatesArr = sessionObj.optJSONArray("ice_candidates") ?: JSONArray()
                    
                    val candidateSdp = candidate.sdp
                    var exists = false
                    for (i in 0 until candidatesArr.length()) {
                        val item = candidatesArr.getJSONObject(i)
                        if (item.optString("candidate") == candidateSdp && item.optString("guardianId") == guardianKey) {
                            exists = true
                            break
                        }
                    }
                    
                    if (!exists) {
                        val candidateObj = JSONObject().apply {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                            put("isReceiver", false)
                            put("guardianId", guardianKey)
                        }
                        candidatesArr.put(candidateObj)

                        val updateObj = JSONObject().apply { put("ice_candidates", candidatesArr) }
                        SupabaseApi.update("sessions", "session_id=eq.$sessionId", updateObj)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving ICE candidate for [$guardianKey]: ${e.message}")
            }
        }
    }

    private fun listenForAnswer() {
        registerSessionListener { record ->
            val rawAnswer = record.optString("webrtc_answer")
            if (rawAnswer.isBlank()) return@registerSessionListener

            // Handle legacy unpartitioned commands and answers
            if (rawAnswer.startsWith("CMD_SWITCH_CAMERA_")) {
                if (rawAnswer != lastHandledCameraCommand) {
                    lastHandledCameraCommand = rawAnswer
                    Log.d(TAG, "Executing legacy camera switch command: $rawAnswer")
                    switchCamera()
                }
                return@registerSessionListener
            } else if (rawAnswer.startsWith("RECONNECT_")) {
                createSenderPeerConnectionForGuardian("default")
                return@registerSessionListener
            }

            // Parse partitioned answers map
            if (rawAnswer.trim().startsWith("{")) {
                try {
                    val answersMap = JSONObject(rawAnswer)
                    val keys = answersMap.keys()
                    while (keys.hasNext()) {
                        val gId = keys.next()
                        val gData = answersMap.optJSONObject(gId) ?: continue
                        
                        // Check for remote camera switch command
                        val cmd = gData.optString("cmd", "")
                        if (cmd.startsWith("CMD_SWITCH_CAMERA_") && cmd != lastHandledCameraCommand) {
                            lastHandledCameraCommand = cmd
                            Log.d(TAG, "Executing camera switch command from guardian [$gId]: $cmd")
                            switchCamera()
                        }

                        val status = gData.optString("status", "")
                        val timestamp = gData.optLong("timestamp", 0L)
                        val lastTimestamp = lastHandledRequestTimestamps[gId] ?: 0L

                        // Check for join/reconnect request
                        if (status == "JOIN_REQUEST" || status == "RECONNECT") {
                            if (timestamp > lastTimestamp || !peerConnections.containsKey(gId)) {
                                lastHandledRequestTimestamps[gId] = timestamp
                                Log.d(TAG, "New connection request from Guardian [$gId]. Creating dedicated mesh connection.")
                                createSenderPeerConnectionForGuardian(gId)
                            }
                        }

                        // Check for incoming SDP answer
                        val answerSdp = gData.optString("answer", "")
                        if (answerSdp.isNotBlank() && answerSdp != lastHandledAnswersMap[gId]) {
                            val pc = peerConnections[gId]
                            if (pc != null && pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                                lastHandledAnswersMap[gId] = answerSdp
                                Log.d(TAG, "Applying WebRTC Answer for Guardian [$gId]")
                                val sdp = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                                pc.setRemoteDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        Log.d(TAG, "WebRTC Answer applied successfully for Guardian [$gId]")
                                        drainPendingIceCandidatesForGuardian(gId)
                                    }
                                }, sdp)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing partitioned answers map: ${e.message}")
                }
            } else if (rawAnswer.startsWith("v=0")) {
                // Legacy plain SDP answer fallback
                val pc = peerConnections["default"]
                if (pc != null && pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, rawAnswer)
                    pc.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            drainPendingIceCandidatesForGuardian("default")
                        }
                    }, sdp)
                }
            }
        }
    }

    private fun drainPendingIceCandidatesForGuardian(guardianKey: String) {
        val list = pendingIceCandidatesMap[guardianKey] ?: return
        val pc = peerConnections[guardianKey] ?: return
        synchronized(list) {
            for (candidate in list) {
                try {
                    pc.addIceCandidate(candidate)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding pending ICE candidate for [$guardianKey]: ${e.message}")
                }
            }
            list.clear()
        }
    }

    // ==========================================
    // RECEIVER IMPLEMENTATION (GUARDIAN DEVICE)
    // ==========================================

    fun startReceiver() {
        Log.d(TAG, "Starting WebRTC Receiver for Guardian: $effectiveGuardianId on session: $sessionId")
        initializeFactory()
        createReceiverPeerConnection()
        listenForOffer()
        listenForIceCandidates()

        // Send JOIN_REQUEST to Sender specifically for this guardian ID
        scope.launch(Dispatchers.IO) {
            try {
                val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                val currentObj = if (rows.length() > 0) rows.getJSONObject(0) else JSONObject()
                val answersMap = try {
                    val raw = currentObj.optString("webrtc_answer", "{}")
                    if (raw.trim().startsWith("{")) JSONObject(raw) else JSONObject()
                } catch (e: Exception) {
                    JSONObject()
                }

                val myEntry = answersMap.optJSONObject(effectiveGuardianId) ?: JSONObject()
                myEntry.put("status", "JOIN_REQUEST")
                myEntry.put("timestamp", System.currentTimeMillis())
                answersMap.put(effectiveGuardianId, myEntry)

                val reqJson = JSONObject().apply {
                    put("webrtc_answer", answersMap.toString())
                }
                SupabaseApi.update("sessions", "session_id=eq.$sessionId", reqJson)
                Log.d(TAG, "Published WebRTC JOIN_REQUEST for guardian $effectiveGuardianId")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending WebRTC JOIN_REQUEST: ${e.message}")
            }
        }
    }

    private fun createReceiverPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        
        receiverPeerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch(Dispatchers.IO) {
                    saveReceiverIceCandidate(candidate)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Receiver connection state changed: $newState")
                onConnectionStateChange(newState)
            }

            override fun onIceConnectionChange(iceState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "Receiver ICE state changed: $iceState")
                if (iceState == PeerConnection.IceConnectionState.CONNECTED || iceState == PeerConnection.IceConnectionState.COMPLETED) {
                    onConnectionStateChange(PeerConnection.PeerConnectionState.CONNECTED)
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

            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
                val track = p0?.track()
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
        })
    }

    private fun listenForOffer() {
        registerSessionListener { record ->
            val rawOffer = record.optString("webrtc_offer")
            if (rawOffer.isBlank()) return@registerSessionListener

            var targetOffer = ""

            // 1. Check partitioned JSON offer for this specific guardian
            if (rawOffer.trim().startsWith("{")) {
                try {
                    val offersMap = JSONObject(rawOffer)
                    val myOfferObj = offersMap.optJSONObject(effectiveGuardianId) 
                        ?: offersMap.optJSONObject("default")
                    if (myOfferObj != null) {
                        targetOffer = myOfferObj.optString("offer", "")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing partitioned offer: ${e.message}")
                }
            } else if (rawOffer.startsWith("v=0")) {
                // 2. Legacy plain SDP offer fallback
                targetOffer = rawOffer
            }

            if (targetOffer.isNotBlank() && targetOffer != lastHandledReceiverOffer) {
                if (receiverPeerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
                    lastHandledReceiverOffer = targetOffer
                    Log.d(TAG, "Guardian [$effectiveGuardianId] applying fresh WebRTC offer from sender")
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, targetOffer)
                    receiverPeerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            drainReceiverPendingIceCandidates()
                            createReceiverAnswer()
                        }
                    }, sdp)
                }
            }
        }
    }

    private fun createReceiverAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        receiverPeerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                receiverPeerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        scope.launch(Dispatchers.IO) {
                            publishReceiverAnswer(sdp.description)
                        }
                    }
                }, sdp)
            }
        }, constraints)
    }

    private fun publishReceiverAnswer(answerSdp: String) {
        try {
            val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
            val currentObj = if (rows.length() > 0) rows.getJSONObject(0) else JSONObject()
            val answersMap = try {
                val raw = currentObj.optString("webrtc_answer", "{}")
                if (raw.trim().startsWith("{")) JSONObject(raw) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }

            val myEntry = answersMap.optJSONObject(effectiveGuardianId) ?: JSONObject()
            myEntry.put("status", "ANSWER")
            myEntry.put("answer", answerSdp)
            myEntry.put("timestamp", System.currentTimeMillis())
            answersMap.put(effectiveGuardianId, myEntry)

            val json = JSONObject().apply { 
                put("webrtc_answer", answersMap.toString()) 
            }
            SupabaseApi.update("sessions", "session_id=eq.$sessionId", json)
            Log.d(TAG, "Guardian [$effectiveGuardianId] WebRTC Answer published to Supabase for session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing guardian answer: ${e.message}")
        }
    }

    private fun saveReceiverIceCandidate(candidate: IceCandidate) {
        synchronized(this) {
            try {
                val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                if (rows.length() > 0) {
                    val sessionObj = rows.getJSONObject(0)
                    val candidatesArr = sessionObj.optJSONArray("ice_candidates") ?: JSONArray()
                    
                    val candidateSdp = candidate.sdp
                    var exists = false
                    for (i in 0 until candidatesArr.length()) {
                        val item = candidatesArr.getJSONObject(i)
                        if (item.optString("candidate") == candidateSdp && item.optString("guardianId") == effectiveGuardianId) {
                            exists = true
                            break
                        }
                    }
                    
                    if (!exists) {
                        val candidateObj = JSONObject().apply {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                            put("isReceiver", true)
                            put("guardianId", effectiveGuardianId)
                        }
                        candidatesArr.put(candidateObj)

                        val updateObj = JSONObject().apply { put("ice_candidates", candidatesArr) }
                        SupabaseApi.update("sessions", "session_id=eq.$sessionId", updateObj)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving guardian ICE candidate: ${e.message}")
            }
        }
    }

    private fun drainReceiverPendingIceCandidates() {
        synchronized(receiverPendingCandidates) {
            for (candidate in receiverPendingCandidates) {
                try {
                    receiverPeerConnection?.addIceCandidate(candidate)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding queued receiver ICE candidate: ${e.message}")
                }
            }
            receiverPendingCandidates.clear()
        }
    }

    // ==========================================
    // COMMON SIGNALING & ICE ROUTING
    // ==========================================

    private val sessionRecordListeners = mutableListOf<(JSONObject) -> Unit>()

    private fun listenForIceCandidates() {
        registerSessionListener { record ->
            val iceArr = record.optJSONArray("ice_candidates") ?: JSONArray()
            for (i in 0 until iceArr.length()) {
                val data = iceArr.getJSONObject(i)
                val sdp = data.optString("candidate")
                val itemIsReceiver = data.optBoolean("isReceiver", false)
                val itemGuardianId = data.optString("guardianId", "default")

                if (sdp.isBlank()) continue

                if (isReceiver) {
                    // Receiver only processes sender's candidates intended for self or default
                    if (!itemIsReceiver && (itemGuardianId == effectiveGuardianId || itemGuardianId == "default")) {
                        if (!receiverProcessedCandidates.contains(sdp)) {
                            receiverProcessedCandidates.add(sdp)
                            val candidate = IceCandidate(
                                data.optString("sdpMid"),
                                data.optInt("sdpMLineIndex"),
                                sdp
                            )
                            synchronized(receiverPendingCandidates) {
                                if (receiverPeerConnection?.remoteDescription != null) {
                                    try {
                                        receiverPeerConnection?.addIceCandidate(candidate)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error adding ICE candidate on receiver: ${e.message}")
                                    }
                                } else {
                                    receiverPendingCandidates.add(candidate)
                                }
                            }
                        }
                    }
                } else {
                    // Sender processes receiver candidates and routes them to the matching guardian PeerConnection
                    if (itemIsReceiver) {
                        val pc = peerConnections[itemGuardianId] ?: peerConnections["default"]
                        val processed = processedCandidatesMap[itemGuardianId]
                        val pending = pendingIceCandidatesMap[itemGuardianId]

                        if (processed != null && !processed.contains(sdp)) {
                            processed.add(sdp)
                            val candidate = IceCandidate(
                                data.optString("sdpMid"),
                                data.optInt("sdpMLineIndex"),
                                sdp
                            )
                            if (pc != null && pc.remoteDescription != null) {
                                try {
                                    pc.addIceCandidate(candidate)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error adding ICE candidate for [$itemGuardianId]: ${e.message}")
                                }
                            } else if (pending != null) {
                                pending.add(candidate)
                            }
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
        val capturer = videoCapturer
        val vSource = videoSource
        val vTrack = videoTrack
        val aSource = audioSource
        val aTrack = audioTrack
        val sRealtime = sessionRealtime
        val fact = factory

        videoCapturer = null
        videoSource = null
        videoTrack = null
        audioSource = null
        audioTrack = null
        sessionRealtime = null
        factory = null

        val rPc = receiverPeerConnection
        receiverPeerConnection = null

        val pcs = ArrayList(peerConnections.values)
        peerConnections.clear()

        scope.launch(Dispatchers.IO) {
            // If receiver, inform sender of disconnect in answers map
            if (isReceiver && effectiveGuardianId.isNotBlank()) {
                try {
                    val rows = SupabaseApi.select("sessions", "session_id=eq.$sessionId")
                    if (rows.length() > 0) {
                        val currentObj = rows.getJSONObject(0)
                        val raw = currentObj.optString("webrtc_answer", "{}")
                        if (raw.trim().startsWith("{")) {
                            val answersMap = JSONObject(raw)
                            answersMap.remove(effectiveGuardianId)
                            val updateJson = JSONObject().apply { put("webrtc_answer", answersMap.toString()) }
                            SupabaseApi.update("sessions", "session_id=eq.$sessionId", updateJson)
                        }
                    }
                } catch (e: Exception) {}
            }

            try { capturer?.stopCapture() } catch (e: Exception) {}
            try { capturer?.dispose() } catch (e: Exception) {}
            try { aTrack?.dispose() } catch (e: Exception) {}
            try { aSource?.dispose() } catch (e: Exception) {}
            try { vTrack?.dispose() } catch (e: Exception) {}
            try { vSource?.dispose() } catch (e: Exception) {}
            try { sRealtime?.stop() } catch (e: Exception) {}
            try { rPc?.close() } catch (e: Exception) {}
            try { rPc?.dispose() } catch (e: Exception) {}
            for (pc in pcs) {
                try { pc.close() } catch (e: Exception) {}
                try { pc.dispose() } catch (e: Exception) {}
            }
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
