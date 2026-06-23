package com.childhelper.app.child.ui.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription

class WebRtcPeerConnectionManager(
    private val scope: CoroutineScope
) {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null

    interface IceEventListener {
        fun onIceConnectionChange(state: PeerConnection.IceConnectionState)
        fun onIceCandidate(candidate: IceCandidate)
        fun onAddStream(stream: MediaStream)
        fun onRemoveStream(stream: MediaStream)
        fun onAddTrack(track: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?)
        fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?)
    }

    fun initializeFactory(context: android.content.Context): EglBase? {
        if (peerConnectionFactory != null) return eglBase

        return try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            eglBase = EglBase.create()

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    org.webrtc.DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
                .setVideoDecoderFactory(
                    org.webrtc.DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = true
                })
                .createPeerConnectionFactory()

            eglBase
        } catch (e: Exception) {
            android.util.Log.e(TAG, "WebRTC factory initialization failed", e)
            eglBase?.release(); eglBase = null; peerConnectionFactory = null
            null
        }
    }

    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        listener: IceEventListener
    ): PeerConnection? {
        val factory = peerConnectionFactory ?: return null
        closeConnection()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                state?.let { listener.onIceConnectionChange(it) }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { listener.onIceCandidate(it) }
            }
            override fun onAddStream(stream: MediaStream?) { stream?.let { listener.onAddStream(it) } }
            override fun onRemoveStream(stream: MediaStream?) { stream?.let { listener.onRemoveStream(it) } }
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                listener.onAddTrack(receiver, streams)
            }
            override fun onRemoveTrack(receiver: RtpReceiver?) { listener.onRemoveTrack(receiver) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        })

        peerConnection = pc
        return pc
    }

    fun closeConnection() {
        peerConnection?.close(); peerConnection?.dispose(); peerConnection = null
    }

    // SDP operations using suspendCancellableCoroutine — non-blocking, eliminates deadlock
    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
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

    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
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

    suspend fun setRemoteDescription(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        val pc = peerConnection ?: return@suspendCancellableCoroutine
        pc.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onSetSuccess() { cont.resume(Unit) {} }
            override fun onSetFailure(e: String?) { cont.resumeWithException(IllegalStateException("setRemote: $e")) }
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    suspend fun <T> collectStats(extractor: (RTCStatsReport) -> T): T {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        return suspendCancellableCoroutine { cont ->
            pc.getStats(RTCStatsCollectorCallback { report ->
                try { cont.resume(extractor(report)) {} }
                catch (e: Exception) { cont.resumeWithException(e) }
            })
        }
    }

    fun getPeerConnection(): PeerConnection? = peerConnection
    fun getPeerConnectionFactory(): PeerConnectionFactory? = peerConnectionFactory
    fun getEglBase(): EglBase? = eglBase

    fun disposeFactory() {
        closeConnection()
        peerConnectionFactory?.dispose(); peerConnectionFactory = null
        eglBase?.release(); eglBase = null
    }

    companion object {
        private const val TAG = "WebRtcP2pManager"
    }
}
