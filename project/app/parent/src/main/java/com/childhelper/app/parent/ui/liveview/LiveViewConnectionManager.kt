package com.childhelper.app.parent.ui.liveview

import android.content.Context
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.signaling.WebRtcSignalingClient
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveViewConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signalingClient: WebRtcSignalingClient,
    private val pairingApi: PairingApi,
    private val securePreferences: SecurePreferences,
    private val scope: CoroutineScope
) {

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

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

    private val lock = Any()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    val currentPeerConnection: PeerConnection?
        get() = peerConnection

    val currentDataChannel: DataChannel?
        get() = dataChannel

    val currentEglBase: EglBase?
        get() = eglBase

    init {
        scope.launch {
            signalingClient.incomingOffers.collect { sdpMessage ->
                handleIncomingCall(sdpMessage.sessionId, sdpMessage.fromDeviceId, sdpMessage.sdp)
            }
        }
    }

    fun initializeWebRtc() {
        if (peerConnectionFactory != null) return

        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            eglBase = EglBase.create()

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    org.webrtc.DefaultVideoEncoderFactory(
                        eglBase!!.eglBaseContext,
                        true,
                        true
                    )
                )
                .setVideoDecoderFactory(
                    org.webrtc.DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
                )
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = false
                })
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            eglBase?.release()
            eglBase = null
            peerConnectionFactory = null
            throw e
        }
    }

    suspend fun connect(childDeviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            withTimeout(30_000) {
                _connectionState.value = LiveConnectionState.CONNECTING
                initializeWebRtc()

                val turnCredentials = pairingApi.getTurnCredentials()
                val iceServers = buildIceServers(turnCredentials)

                val pc: PeerConnection = createPeerConnection(iceServers)
                    ?: return@withTimeout Result.failure<Unit>(Exception("Failed to create peer connection"))

                val recvOnly = RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                )
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)

                val dc = pc.createDataChannel("talkback", DataChannel.Init().apply {
                    ordered = true
                })

                val sid = "lv-${UUID.randomUUID()}"

                peerConnection = pc
                dataChannel = dc
                sessionId = sid

                val offer = createOffer(pc)
                signalingClient.sendOffer(
                    sessionId = sid,
                    toDeviceId = childDeviceId,
                    sessionDescription = offer
                ).getOrThrow()

                _connectionState.value = LiveConnectionState.SIGNALING

                iceCollectionJob = scope.launch {
                    signalingClient.incomingIceCandidates.collect { msg ->
                        if (msg.sessionId == sid) {
                            pc.addIceCandidate(
                                IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                            )
                        }
                    }
                }

                val answerMsg = signalingClient.incomingAnswers.first { it.sessionId == sid }
                val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, answerMsg.sdp)
                setRemoteDescription(pc, answerSdp)
                _connectionState.value = LiveConnectionState.CONNECTED
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            _connectionState.value = LiveConnectionState.FAILED
            Result.failure(Exception("Connection timed out"))
        } catch (e: Exception) {
            _connectionState.value = LiveConnectionState.FAILED
            Result.failure(e)
        }
    }

    fun disconnect() {
        synchronized(lock) {
            iceCollectionJob?.cancel()
            iceCollectionJob = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            dataChannel?.close()
            dataChannel = null
            _remoteVideoTrack.value = null
            _connectionState.value = LiveConnectionState.CLOSED
            sessionId = null
        }
    }

    fun fullCleanup() {
        disconnect()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }

    private fun buildIceServers(
        credentials: com.childhelper.core.network.model.TurnCredentials
    ): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        servers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        for (stunUrl in credentials.stunUrls) {
            servers.add(
                PeerConnection.IceServer.builder(stunUrl).createIceServer()
            )
        }
        for (turnUrl in credentials.urls) {
            servers.add(
                PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(credentials.username)
                    .setPassword(credentials.password)
                    .createIceServer()
            )
        }
        return servers
    }

    private fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>
    ): PeerConnection? {
        val factory = peerConnectionFactory ?: return null

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        return factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                handler.post {
                    _connectionState.value = when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> LiveConnectionState.CONNECTED
                        PeerConnection.IceConnectionState.DISCONNECTED -> LiveConnectionState.DISCONNECTED
                        PeerConnection.IceConnectionState.FAILED -> LiveConnectionState.FAILED
                        PeerConnection.IceConnectionState.CLOSED -> LiveConnectionState.CLOSED
                        else -> _connectionState.value
                    }
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { ice ->
                    scope.launch {
                        val sid = sessionId ?: return@launch
                        val childId = securePreferences.getString("paired_child_device_id", "") ?: return@launch
                        if (childId.isBlank()) return@launch
                        signalingClient.sendIceCandidate(
                            sessionId = sid,
                            toDeviceId = childId,
                            candidate = ice
                        )
                    }
                }
            }
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    handler.post { _remoteVideoTrack.value = track }
                }
            }
            override fun onRemoveStream(stream: MediaStream?) {
                handler.post { _remoteVideoTrack.value = null }
            }
            override fun onDataChannel(dc: DataChannel?) {
                if (dc?.label() == "talkback") {
                    dataChannel?.close()
                    dataChannel = dc
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(
                receiver: RtpReceiver?,
                streams: Array<out MediaStream>?
            ) {
                receiver?.track()?.let { track ->
                    if (track is VideoTrack) {
                        handler.post { _remoteVideoTrack.value = track }
                    }
                }
            }
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        })
    }

    private fun createOffer(pc: PeerConnection): SessionDescription {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        val observer = CompletableSdpObserver()
        pc.createOffer(observer, constraints)
        val offer = observer.get() ?: throw IllegalStateException("Failed to create offer")

        val localObserver = CompletableSdpObserver()
        pc.setLocalDescription(localObserver, offer)
        localObserver.await()

        return offer
    }

    private fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription) {
        val observer = CompletableSdpObserver()
        pc.setRemoteDescription(observer, sdp)
        observer.await()
    }

    private fun handleIncomingCall(sid: String, fromDeviceId: String, offerSdp: String) {
        scope.launch {
            try {
                disconnect()
                initializeWebRtc()

                val turnCredentials = pairingApi.getTurnCredentials()
                val iceServers = buildIceServers(turnCredentials)

                val pc: PeerConnection = createPeerConnection(iceServers)
                    ?: throw IllegalStateException("Failed to create peer connection")

                val recvOnly = RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                )
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
                pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)

                val dc = pc.createDataChannel("talkback", DataChannel.Init().apply { ordered = true })

                synchronized(lock) {
                    peerConnection = pc
                    dataChannel = dc
                    sessionId = sid
                }

                setRemoteDescription(pc, SessionDescription(SessionDescription.Type.OFFER, offerSdp))

                val answer = createAnswer(pc)
                signalingClient.sendAnswer(
                    sessionId = sid,
                    toDeviceId = fromDeviceId,
                    sessionDescription = answer
                ).getOrThrow()

                iceCollectionJob = scope.launch {
                    signalingClient.incomingIceCandidates.collect { msg ->
                        if (msg.sessionId == sid) {
                            pc.addIceCandidate(
                                IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                            )
                        }
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

    private fun createAnswer(pc: PeerConnection): SessionDescription {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        val observer = CompletableSdpObserver()
        pc.createAnswer(observer, constraints)
        val answer = observer.get() ?: throw IllegalStateException("Failed to create answer")

        val localObserver = CompletableSdpObserver()
        pc.setLocalDescription(localObserver, answer)
        localObserver.await()

        return answer
    }

    private class CompletableSdpObserver : org.webrtc.SdpObserver {
        private val lock = Object()
        private var sessionDescription: SessionDescription? = null
        private var error: String? = null

        override fun onCreateSuccess(sdp: SessionDescription?) {
            sessionDescription = sdp
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onSetSuccess() {
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onCreateFailure(error: String?) {
            this.error = error
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onSetFailure(error: String?) {
            this.error = error
            synchronized(lock) { lock.notifyAll() }
        }

        fun get(): SessionDescription? {
            synchronized(lock) {
                if (sessionDescription == null && error == null) {
                    lock.wait(10000)
                }
            }
            return sessionDescription
        }

        fun await() {
            synchronized(lock) {
                lock.wait(10000)
            }
        }
    }
}
