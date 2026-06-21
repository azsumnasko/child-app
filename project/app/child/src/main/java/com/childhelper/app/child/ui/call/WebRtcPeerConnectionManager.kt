package com.childhelper.app.child.ui.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

/**
 * Manages the WebRTC PeerConnection lifecycle and SDP negotiation.
 *
 * Responsible for:
 * - Creating and disposing the [PeerConnectionFactory]
 * - Creating and closing [PeerConnection] instances
 * - Creating and setting SDP offers and answers
 * - Adding remote ICE candidates
 * - Collecting connection statistics
 * - Forwarding ICE connection state changes to a listener
 *
 * This class is intentionally focused: it knows about WebRTC peer connections
 * but nothing about cameras, audio devices, or call state machines.
 *
 * @param scope The coroutine scope used for async callbacks from the native WebRTC layer.
 */
class WebRtcPeerConnectionManager(
    private val scope: CoroutineScope
) {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null

    /**
     * Callback interface for ICE-related events produced by the peer connection.
     */
    interface IceEventListener {
        fun onIceConnectionChange(state: PeerConnection.IceConnectionState)
        fun onIceCandidate(candidate: IceCandidate)
        fun onAddStream(stream: MediaStream)
        fun onRemoveStream(stream: MediaStream)
        fun onAddTrack(track: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?)
        fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?)
    }

    /**
     * Initialize the [PeerConnectionFactory] if it hasn't been created yet.
     *
     * Must be called before [createPeerConnection].
     *
     * @param context Android context required for WebRTC initialization.
     * @return The created (or existing) [EglBase] context.
     */
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
                    org.webrtc.DefaultVideoEncoderFactory(
                        eglBase?.eglBaseContext,
                        true,
                        true
                    )
                )
                .setVideoDecoderFactory(
                    org.webrtc.DefaultVideoDecoderFactory(eglBase?.eglBaseContext)
                )
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = false
                })
                .createPeerConnectionFactory()

            eglBase
        } catch (e: Exception) {
            android.util.Log.e(TAG, "WebRTC factory initialization failed", e)
            eglBase?.release()
            eglBase = null
            peerConnectionFactory = null
            null
        }
    }

    /**
     * Create a new [PeerConnection] with the given ICE servers and event listener.
     *
     * Closes any existing connection before creating a new one.
     *
     * @param iceServers List of STUN/TURN servers.
     * @param listener Callback for ICE events.
     * @return The created [PeerConnection], or null if the factory is not initialized.
     */
    fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
        listener: IceEventListener
    ): PeerConnection? {
        val factory = peerConnectionFactory ?: return null

        closeConnection()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        val pc = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    state?.let { listener.onIceConnectionChange(it) }
                }
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { listener.onIceCandidate(it) }
                }
                override fun onAddStream(stream: MediaStream?) {
                    stream?.let { listener.onAddStream(it) }
                }
                override fun onRemoveStream(stream: MediaStream?) {
                    stream?.let { listener.onRemoveStream(it) }
                }
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    streams: Array<out MediaStream>?
                ) {
                    listener.onAddTrack(receiver, streams)
                }
                override fun onRemoveTrack(receiver: RtpReceiver?) {
                    listener.onRemoveTrack(receiver)
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            }
        )

        peerConnection = pc
        return pc
    }

    /**
     * Close and dispose the current [PeerConnection], if any.
     */
    fun closeConnection() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    /**
     * Create an SDP offer for initiating a call.
     *
     * @return The created [SessionDescription] offer.
     * @throws IllegalStateException if no peer connection exists.
     */
    fun createOffer(): SessionDescription {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        val observer = CompletableSdpObserver()
        pc.createOffer(observer, constraints)
        val offer = observer.get()

        val localObserver = CompletableSdpObserver()
        pc.setLocalDescription(localObserver, offer)
        localObserver.await()

        return offer
    }

    /**
     * Create an SDP answer in response to an incoming call.
     *
     * @return The created [SessionDescription] answer.
     * @throws IllegalStateException if no peer connection exists.
     */
    fun createAnswer(): SessionDescription {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        val observer = CompletableSdpObserver()
        pc.createAnswer(observer, constraints)
        val answer = observer.get()

        val localObserver = CompletableSdpObserver()
        pc.setLocalDescription(localObserver, answer)
        localObserver.await()

        return answer
    }

    /**
     * Set the remote session description (offer or answer).
     *
     * @param sdp The remote [SessionDescription].
     */
    fun setRemoteDescription(sdp: SessionDescription) {
        val pc = peerConnection ?: return
        val observer = CompletableSdpObserver()
        pc.setRemoteDescription(observer, sdp)
        observer.await()
    }

    /**
     * Add a remote ICE candidate to the peer connection.
     *
     * @param candidate The [IceCandidate] received from the remote peer.
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Asynchronously collect connection statistics.
     *
     * @param extractor Function that extracts a value of type [T] from the stats report.
     * @return The extracted value.
     */
    suspend fun <T> collectStats(extractor: (RTCStatsReport) -> T): T {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection not initialized")
        return suspendCancellableCoroutine { continuation ->
            pc.getStats(RTCStatsCollectorCallback { report ->
                try {
                    val result = extractor(report)
                    continuation.resume(result) {}
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            })
        }
    }

    /**
     * Return the current [PeerConnection], or null if none exists.
     */
    fun getPeerConnection(): PeerConnection? = peerConnection

    /**
     * Return the current [PeerConnectionFactory], or null if not initialized.
     */
    fun getPeerConnectionFactory(): PeerConnectionFactory? = peerConnectionFactory

    /**
     * Return the [EglBase] context used by the factory, or null.
     */
    fun getEglBase(): EglBase? = eglBase

    /**
     * Dispose the factory and EglBase. This is irreversible;
     * [initializeFactory] must be called again to create new connections.
     */
    fun disposeFactory() {
        closeConnection()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }

    /**
     * Internal helper that bridges WebRTC's callback-based SDP API to a synchronous style.
     */
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

        fun get(): SessionDescription {
            synchronized(lock) {
                if (sessionDescription == null && error == null) {
                    lock.wait(10000)
                }
            }
            return sessionDescription
                ?: error?.let { throw IllegalStateException("SDP failure: $it") }
                ?: throw IllegalStateException("SDP creation timed out after 10s")
        }

        fun await() {
            synchronized(lock) {
                if (sessionDescription == null && error == null) {
                    lock.wait(10000)
                }
            }
            error?.let { throw IllegalStateException("SDP set failed: $it") }
        }
    }

    companion object {
        private const val TAG = "WebRtcP2pManager"
    }
}
