package com.childhelper.app.parent.ui.liveview

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.childhelper.core.common.signaling.SdpMessage
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.signaling.WebRtcSignalingClient
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID

class LiveViewConnectionManager(
    @ApplicationContext private val context: Context,
    private val signalingClient: WebRtcSignalingClient,
    private val pairingApi: PairingApi,
    private val securePreferences: SecurePreferences,
    private val scope: CoroutineScope
) {

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _remoteAudioTrack = MutableStateFlow<org.webrtc.AudioTrack?>(null)
    val remoteAudioTrack: StateFlow<org.webrtc.AudioTrack?> = _remoteAudioTrack.asStateFlow()

    private val _connectionState = MutableStateFlow(LiveConnectionState.IDLE)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _incomingCall = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val incomingCall: SharedFlow<Boolean> = _incomingCall.asSharedFlow()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var sessionId: String? = null
    private var iceCollectionJob: Job? = null
    private var audioKeepAliveJob: Job? = null

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var wasSpeakerphoneOn: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private val lock = Any()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    val currentPeerConnection: PeerConnection? get() = peerConnection
    val currentDataChannel: DataChannel? get() = dataChannel
    val currentEglBase: EglBase? get() = eglBase

    init {
        scope.launch {
            signalingClient.incomingOffers.collect { sdpMessage ->
                if (_connectionState.value == LiveConnectionState.IDLE ||
                    _connectionState.value == LiveConnectionState.CLOSED ||
                    _connectionState.value == LiveConnectionState.FAILED) {
                    handleIncomingCall(sdpMessage.sessionId, sdpMessage.fromDeviceId, sdpMessage.sdp)
                } else {
                    debugLog("init: ignoring incoming offer while in state ${_connectionState.value}")
                }
            }
        }
    }

    private fun debugLog(msg: String) {
        // Production: no file logging
    }

    private suspend fun waitForAnswer(sid: String): SdpMessage = withTimeout(120_000) {
        signalingClient.incomingAnswers.first { it.sessionId == sid }
    }

    private fun configureAudioForCall() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        previousAudioMode = am.mode
        wasSpeakerphoneOn = am.isSpeakerphoneOn
        try {
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
            debugLog("connect: STREAM_VOICE_CALL set to max=$maxVol")
        } catch (e: Exception) { debugLog("connect: setStreamVolume failed: ${e.message}") }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { debugLog("audioFocus: change=$it") }
                .build()
            try { debugLog("connect: audioFocus request result=${am.requestAudioFocus(audioFocusRequest!!)}") }
            catch (e: Exception) { debugLog("connect: audioFocus failed: ${e.message}") }
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        debugLog("connect: audio configured")
    }

    private fun restoreAudioAfterCall() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            }
            am.isSpeakerphoneOn = wasSpeakerphoneOn
            am.mode = previousAudioMode
            debugLog("disconnect: audio restored (mode=$previousAudioMode)")
        } catch (e: Exception) { debugLog("disconnect: audio restore failed: ${e.message}") }
    }

    private fun startAudioKeepAlive() {
        audioKeepAliveJob?.cancel()
        audioKeepAliveJob = scope.launch {
            var tick = 0
            while (isActive) {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am?.mode != AudioManager.MODE_IN_COMMUNICATION) {
                        am?.mode = AudioManager.MODE_IN_COMMUNICATION
                        am?.isSpeakerphoneOn = true
                        debugLog("audioKeepAlive: mode re-asserted")
                    }
                    tick++
                    if (tick % 3 == 0) {
                        peerConnection?.let { debugLog("keepAlive: tick=$tick iceState=${it.iceConnectionState()}") }
                    }
                } catch (e: Exception) { debugLog("audioKeepAlive: fail ${e.message}") }
                delay(4000)
            }
        }
    }

    fun initializeWebRtc() {
        synchronized(lock) {
            if (peerConnectionFactory != null) return
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                eglBase = EglBase.create()
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
                    .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                    .setOptions(PeerConnectionFactory.Options().apply {
                        disableEncryption = false
                        disableNetworkMonitor = true
                    })
                    .createPeerConnectionFactory()
            } catch (e: Exception) {
                eglBase?.release(); eglBase = null; peerConnectionFactory = null; throw e
            }
        }
    }

    suspend fun connect(childDeviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            debugLog("connect: start childId=$childDeviceId")
            disconnect()
            withTimeout(120_000) {
                _connectionState.value = LiveConnectionState.CONNECTING
                debugLog("connect: configuring audio routing...")
                configureAudioForCall()
                debugLog("connect: init WebRTC...")
                initializeWebRtc()
                val iceServers = buildIceServers()
                debugLog("connect: ICE OK, creating PC...")
                val pc: PeerConnection = createPeerConnection(iceServers)
                    ?: return@withTimeout Result.failure<Unit>(Exception("Failed to create peer connection"))
                debugLog("connect: PC created")
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(
                        org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(
                        org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                val dc = pc.createDataChannel("talkback", DataChannel.Init().apply { ordered = true })
                val sid = "lv-${UUID.randomUUID()}"
                peerConnection = pc; dataChannel = dc; sessionId = sid

                val offer = createOffer(pc)
                debugLog("connect: offer created, sending...")
                signalingClient.sendOffer(sessionId = sid, toDeviceId = childDeviceId, sessionDescription = offer).getOrThrow()
                debugLog("connect: offer SENT, waiting for answer (sid=$sid)...")

                _connectionState.value = LiveConnectionState.SIGNALING
                iceCollectionJob = scope.launch {
                    signalingClient.incomingIceCandidates.collect { msg ->
                        if (msg.sessionId == sid) {
                            pc.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate))
                        }
                    }
                }

                val answerMsg = waitForAnswer(sid)
                debugLog("connect: GOT ANSWER")
                setRemoteDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, answerMsg.sdp))
                _connectionState.value = LiveConnectionState.CONNECTED
                debugLog("connect: CONNECTED!")
                startAudioKeepAlive()
            }
            debugLog("connect: success")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            debugLog("connect: TIMEOUT")
            _connectionState.value = LiveConnectionState.FAILED
            Result.failure(Exception("Connection timed out"))
        } catch (e: Exception) {
            debugLog("connect: FAILED - ${e.javaClass.simpleName}: ${e.message}")
            _connectionState.value = LiveConnectionState.FAILED
            Result.failure(e)
        }
    }

    fun disconnect() {
        synchronized(lock) {
            iceCollectionJob?.cancel(); iceCollectionJob = null
            audioKeepAliveJob?.cancel(); audioKeepAliveJob = null
            peerConnection?.close(); peerConnection?.dispose(); peerConnection = null
            dataChannel?.close(); dataChannel = null
            _remoteVideoTrack.value = null
            _remoteAudioTrack.value = null
            _connectionState.value = LiveConnectionState.CLOSED
            sessionId = null
        }
        restoreAudioAfterCall()
    }

    fun fullCleanup() {
        disconnect()
        peerConnectionFactory?.dispose(); peerConnectionFactory = null
        eglBase?.release(); eglBase = null
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>): PeerConnection? {
        val factory = peerConnectionFactory ?: return null
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        return factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                debugLog("onIceConnectionChange: state=$state")
                handler.post {
                    _connectionState.value = when (state) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> LiveConnectionState.CONNECTED
                        PeerConnection.IceConnectionState.DISCONNECTED -> LiveConnectionState.DISCONNECTED
                        PeerConnection.IceConnectionState.FAILED -> LiveConnectionState.FAILED
                        PeerConnection.IceConnectionState.CLOSED -> LiveConnectionState.CLOSED
                        else -> _connectionState.value
                    }
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) { debugLog("onIceGatheringChange: state=$state") }
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { ice ->
                    scope.launch {
                        val sid = sessionId ?: return@launch
                        val childId = securePreferences.getString("paired_child_device_id", "") ?: return@launch
                        if (childId.isBlank()) return@launch
                        signalingClient.sendIceCandidate(sessionId = sid, toDeviceId = childId, candidate = ice)
                    }
                }
            }
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { handler.post { _remoteVideoTrack.value = it } }
                stream?.audioTracks?.firstOrNull()?.let { it.setEnabled(true); it.setVolume(1.0); handler.post { _remoteAudioTrack.value = it } }
            }
            override fun onRemoveStream(stream: MediaStream?) {
                handler.post { _remoteVideoTrack.value = null; _remoteAudioTrack.value = null }
            }
            override fun onDataChannel(dc: DataChannel?) {
                if (dc?.label() == "talkback") { dataChannel?.close(); dataChannel = dc }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                receiver?.track()?.let { track ->
                    when (track) {
                        is VideoTrack -> handler.post { _remoteVideoTrack.value = track }
                        is org.webrtc.AudioTrack -> {
                            track.setEnabled(true); track.setVolume(1.0)
                            handler.post { _remoteAudioTrack.value = track }
                            debugLog("onAddTrack: AudioTrack received, enabled=${track.enabled()}, id=${track.id()}")
                        }
                    }
                }
            }
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        })
    }

    // SDP operations using suspendCancellableCoroutine — non-blocking, eliminates deadlock risk
    private suspend fun createOffer(pc: PeerConnection): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc.createOffer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    pc.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onSetSuccess() { cont.resume(sdp) {} }
                        override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException("setLocal: $e")) }
                        override fun onCreateSuccess(s: SessionDescription?) {}
                        override fun onCreateFailure(e: String?) {}
                    }, sdp)
                } else cont.resumeWithException(IllegalStateException("offer null"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException("createOffer: $e")) }
            override fun onSetFailure(e: String?) {}
        }, constraints)
    }

    private suspend fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onSetSuccess() { cont.resume(Unit) {} }
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException("setRemote: $e")) }
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
        }, sdp)
    }

    private suspend fun createAnswer(pc: PeerConnection): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc.createAnswer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    pc.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onSetSuccess() { cont.resume(sdp) {} }
                        override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException("setLocal: $e")) }
                        override fun onCreateSuccess(s: SessionDescription?) {}
                        override fun onCreateFailure(e: String?) {}
                    }, sdp)
                } else cont.resumeWithException(IllegalStateException("answer null"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) { cont.resumeWithException(IllegalStateException("createAnswer: $e")) }
            override fun onSetFailure(e: String?) {}
        }, constraints)
    }

    private fun handleIncomingCall(sid: String, fromDeviceId: String, offerSdp: String) {
        scope.launch {
            try {
                disconnect()
                configureAudioForCall()
                initializeWebRtc()
                val pc: PeerConnection = createPeerConnection(buildIceServers())
                    ?: throw IllegalStateException("Failed to create peer connection")
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                val dc = pc.createDataChannel("talkback", DataChannel.Init().apply { ordered = true })
                synchronized(lock) { peerConnection = pc; dataChannel = dc; sessionId = sid }

                setRemoteDescription(pc, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
                val answer = createAnswer(pc)
                signalingClient.sendAnswer(sessionId = sid, toDeviceId = fromDeviceId, sessionDescription = answer).getOrThrow()

                iceCollectionJob = scope.launch {
                    signalingClient.incomingIceCandidates.collect { msg ->
                        if (msg.sessionId == sid) pc.addIceCandidate(IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate))
                    }
                }
                _connectionState.value = LiveConnectionState.CONNECTED
                _incomingCall.tryEmit(true)
            } catch (e: Exception) {
                android.util.Log.e("LiveViewCM", "Incoming call failed", e)
                disconnect()
            }
        }
    }
}
