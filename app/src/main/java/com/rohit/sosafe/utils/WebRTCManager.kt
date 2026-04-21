package com.rohit.sosafe.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.rohit.sosafe.data.contracts.SoSafeContract
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.*

class WebRTCManager(
    private val context: Context,
    private val sessionId: String,
    private val onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit,
    private val onAudioTrackReceived: (AudioTrack) -> Unit = {}
) {
    private val TAG = "WebRTC_MANAGER"
    private val db = Firebase.firestore
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private val processedCandidates = mutableSetOf<String>()
    
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    private fun initializeFactory() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        
        // IMPROVED: Use VOICE_COMMUNICATION for better mic sensitivity and hardware processing
        val audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        
        audioDeviceModule.release()
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

        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
                            .update(SoSafeContract.Fields.WEBRTC_OFFER, sdp.description)
                    }
                }, sdp)
            }
        }, MediaConstraints())

        listenForAnswer()
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
                val candidateMap = mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp
                )
                db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
                    .update(SoSafeContract.Fields.ICE_CANDIDATES, FieldValue.arrayUnion(candidateMap))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection state changed: $newState")
                onConnectionStateChange(newState)
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                if (transceiver.receiver.track() is AudioTrack) {
                    onAudioTrackReceived(transceiver.receiver.track() as AudioTrack)
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
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

    private fun listenForOffer() {
        db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
            .addSnapshotListener { snapshot, _ ->
                val offer = snapshot?.getString(SoSafeContract.Fields.WEBRTC_OFFER)
                if (offer != null && peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
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
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
                            .update(SoSafeContract.Fields.WEBRTC_ANSWER, sdp.description)
                    }
                }, sdp)
            }
        }, MediaConstraints())
    }

    private fun listenForAnswer() {
        db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
            .addSnapshotListener { snapshot, _ ->
                val answer = snapshot?.getString(SoSafeContract.Fields.WEBRTC_ANSWER)
                if (answer != null && peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, answer)
                    peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                }
            }
    }

    private fun listenForIceCandidates() {
        db.collection(SoSafeContract.Collections.SESSIONS).document(sessionId)
            .addSnapshotListener { snapshot, _ ->
                @Suppress("UNCHECKED_CAST")
                val candidates = snapshot?.get(SoSafeContract.Fields.ICE_CANDIDATES) as? List<Map<String, Any>>
                candidates?.forEach { data ->
                    val sdp = data["candidate"] as? String ?: return@forEach
                    if (!processedCandidates.contains(sdp)) {
                        val candidate = IceCandidate(
                            data["sdpMid"] as String,
                            (data["sdpMLineIndex"] as Long).toInt(),
                            sdp
                        )
                        peerConnection?.addIceCandidate(candidate)
                        processedCandidates.add(sdp)
                    }
                }
            }
    }

    fun stop() {
        peerConnection?.close()
        peerConnection = null
        factory?.dispose()
        factory = null
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.e("WebRTC", "SDP Create Failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.e("WebRTC", "SDP Set Failure: $p0") }
    }
}
