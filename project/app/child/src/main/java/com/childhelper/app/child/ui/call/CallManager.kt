package com.childhelper.app.child.ui.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.childhelper.core.common.model.CallSession
import com.childhelper.core.common.model.CallStatus
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.signaling.WebRtcSignalingClient
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpSender
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade that orchestrates WebRTC-based video and audio calls for the child app.
 *
 * This class is the **single public entry point** for call management. It delegates
 * all specialized work to three focused managers:
 *
 * - [WebRtcPeerConnectionManager] — peer connection lifecycle, SDP negotiation, ICE
 * - [CameraCaptureManager] — camera enumeration, capture, switching
 * - [AudioDeviceManager] — audio track, mute/unmute, speakerphone, talk-back
 *
 * The public API is kept identical to the original monolithic implementation so that
 * existing callers (ViewModels, Services, Composables) require zero changes.
 *
 * Features:
 * - One-tap calling to guardians
 * - WebRTC video + audio with peer connection
 * - Audio-only fallback when camera unavailable
 * - Call state management (connecting, ringing, connected, ended)
 * - Proper cleanup of all WebRTC resources
 *
 * Privacy note: All call data is peer-to-peer via WebRTC. No media is recorded or stored.
 *
 * @param context Android application context
 * @param signalingClient WebRTC signaling client for offer/answer/ICE exchange
 * @param securePreferences Secure storage for device ID retrieval
 * @param scope Coroutine scope for async WebRTC operations
 * @param peerConnectionManager Low-level WebRTC peer connection management
 * @param cameraCaptureManager Local camera capture management
 * @param audioDeviceManager Local audio capture and device routing management
 */
@Singleton
class CallManager(
    @ApplicationContext private val context: Context,
    private val signalingClient: WebRtcSignalingClient,
    private val securePreferences: SecurePreferences,
    private val scope: CoroutineScope,
    private val peerConnectionManager: WebRtcPeerConnectionManager,
    private val cameraCaptureManager: CameraCaptureManager,
    private val audioDeviceManager: AudioDeviceManager,
    private val pairingApi: PairingApi
) {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _currentSession = MutableStateFlow<CallSession?>(null)
    val currentSession: StateFlow<CallSession?> = _currentSession.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _isAudioOnly = MutableStateFlow(false)
    val isAudioOnly: StateFlow<Boolean> = _isAudioOnly.asStateFlow()

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>()
    val connectionEvents: Flow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    /** Flow that emits the current adaptive video quality tier during calls. */
    private val _videoQuality = MutableStateFlow(VideoQualityTier.HIGH)
    val videoQuality: StateFlow<VideoQualityTier> = _videoQuality.asStateFlow()

    // CRIT-3 FIX: @Volatile ensures visibility across threads — this field is written
    // from the main/Handler thread during cleanup and read from coroutine threads.
    @Volatile private var adaptiveBitrateController: AdaptiveBitrateController? = null
    private val handler = Handler(Looper.getMainLooper())

    init {
        scope.launch {
            signalingClient.incomingOffers.collect { sdpMessage ->
                handleIncomingOffer(
                    sdpMessage.sessionId,
                    sdpMessage.fromDeviceId,
                    SessionDescription(SessionDescription.Type.OFFER, sdpMessage.sdp)
                )
            }
        }
        scope.launch {
            signalingClient.incomingIceCandidates.collect { iceMessage ->
                peerConnectionManager.addIceCandidate(
                    IceCandidate(iceMessage.sdpMid, iceMessage.sdpMLineIndex, iceMessage.candidate)
                )
            }
        }
        scope.launch {
            signalingClient.incomingAnswers.collect { answer ->
                peerConnectionManager.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, answer.sdp)
                )
            }
        }
    }

    /**
     * Initialize WebRTC peer connection factory.
     * Must be called before any call operations.
     */
    fun initializeWebRtc() {
        peerConnectionManager.initializeFactory(context)
    }

    /**
     * Initiate a call to a guardian device.
     *
     * @param toDeviceId The device ID of the guardian to call
     * @param hasVideo Whether to include video in the call
     */
    fun initiateCall(toDeviceId: String, hasVideo: Boolean = true) {
        scope.launch {
            try {
                initializeWebRtc()
                val childDeviceId = getChildDeviceId()

                val session = CallSession(
                    callerId = childDeviceId,
                    calleeId = toDeviceId,
                    hasVideo = hasVideo,
                    status = CallStatus.CONNECTING
                )

                _currentSession.value = session
                _isAudioOnly.value = !hasVideo
                _callState.value = CallState.Connecting(session.sessionId)

                val turnCreds = try {
                    pairingApi.getTurnCredentials()
                } catch (e: Exception) {
                    null
                }

                val iceServers = mutableListOf<PeerConnection.IceServer>(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                )

                turnCreds?.let { creds ->
                    creds.urls.forEach { url ->
                        iceServers.add(
                            PeerConnection.IceServer.builder(url)
                                .setUsername(creds.username)
                                .setPassword(creds.password)
                                .createIceServer()
                        )
                    }
                }

                val pc = createPeerConnectionWithListener(iceServers)

                val factory = peerConnectionManager.getPeerConnectionFactory()
                    ?: throw IllegalStateException("PeerConnectionFactory not initialized")
                val eglBase = peerConnectionManager.getEglBase()

                if (!hasVideo) {
                    audioDeviceManager.startAudioCapture(factory, pc)
                } else {
                    cameraCaptureManager.startCapture(factory, eglBase, pc)
                    audioDeviceManager.startAudioCapture(factory, pc)
                }

                // Create and send offer via signaling (off main thread to avoid ANR)
                val offer = withContext(Dispatchers.IO) { peerConnectionManager.createOffer() }
                signalingClient.sendOffer(
                    sessionId = session.sessionId,
                    toDeviceId = toDeviceId,
                    sessionDescription = offer
                ).getOrThrow()

                _callState.value = CallState.Ringing(session.sessionId)
                _currentSession.value = session.copy(status = CallStatus.RINGING)
            } catch (e: Exception) {
                _callState.value = CallState.Error(e.message ?: "Failed to initiate call")
                cleanup()
            }
        }
    }

    /**
     * Accept an incoming call.
     *
     * @param sessionId The session ID of the incoming call
     */
    fun acceptCall(sessionId: String) {
        scope.launch {
            try {
                _callState.value = CallState.Connecting(sessionId)

                val answer = withContext(Dispatchers.IO) { peerConnectionManager.createAnswer() }
                val callerId = _currentSession.value?.callerId ?: return@launch
                signalingClient.sendAnswer(
                    sessionId = sessionId,
                    toDeviceId = callerId,
                    sessionDescription = answer
                ).getOrThrow()

                _currentSession.value?.let { session ->
                    _currentSession.value = session.copy(
                        status = CallStatus.CONNECTED,
                        startTime = System.currentTimeMillis()
                    )
                }
                _callState.value = CallState.Connected(sessionId)
            } catch (e: Exception) {
                _callState.value = CallState.Error(e.message ?: "Failed to accept call")
            }
        }
    }

    /**
     * Handle an incoming SDP offer from a remote peer.
     *
     * Creates a [CallSession], sets the remote description on the peer connection,
     * then delegates to [acceptCall] to create and send the answer SDP.
     *
     * @param sessionId The call session identifier from signaling.
     * @param callerId The device ID of the peer who initiated the call.
     * @param offer The remote SDP offer [SessionDescription].
     */
    fun handleIncomingOffer(sessionId: String, callerId: String, offer: SessionDescription) {
        scope.launch {
            try {
                cleanup() // Dispose any existing call before starting new one
                initializeWebRtc()
                val childDeviceId = getChildDeviceId()

                val turnCreds = try {
                    pairingApi.getTurnCredentials()
                } catch (e: Exception) {
                    null
                }

                val iceServers = mutableListOf<PeerConnection.IceServer>(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                )
                turnCreds?.let { creds ->
                    creds.urls.forEach { url ->
                        iceServers.add(
                            PeerConnection.IceServer.builder(url)
                                .setUsername(creds.username)
                                .setPassword(creds.password)
                                .createIceServer()
                        )
                    }
                }

                val session = CallSession(
                    callerId = callerId,
                    calleeId = childDeviceId,
                    hasVideo = true,
                    status = CallStatus.CONNECTING
                )

                createPeerConnectionWithListener(iceServers)

                _currentSession.value = session
                _callState.value = CallState.Incoming(sessionId, callerId)

                setRemoteDescription(offer)

                val factory = peerConnectionManager.getPeerConnectionFactory()
                    ?: throw IllegalStateException("PeerConnectionFactory not initialized")
                val eglBase = peerConnectionManager.getEglBase()
                cameraCaptureManager.startCapture(factory, eglBase, peerConnectionManager.getPeerConnection())
                audioDeviceManager.startAudioCapture(factory, peerConnectionManager.getPeerConnection())

                acceptCall(sessionId)
            } catch (e: Exception) {
                _callState.value = CallState.Error(e.message ?: "Failed to handle incoming call")
                cleanup()
            }
        }
    }

    /**
     * End the current call and clean up resources.
     */
    fun endCall() {
        if (_callState.value is CallState.Idle || _callState.value is CallState.Ended) return
        val sessionId = _currentSession.value?.sessionId

        _currentSession.value?.let { session ->
            _currentSession.value = session.copy(
                status = CallStatus.ENDED,
                endTime = System.currentTimeMillis()
            )
        }

        _callState.value = CallState.Ended(sessionId ?: "")

        cleanup()

        scope.launch {
            // Notify signaling server about call end
            try {
                sessionId?.let { sid ->
                    val session = _currentSession.value ?: return@launch
                    val childDeviceId = getChildDeviceId()
                    val toDeviceId = if (session.callerId == childDeviceId) session.calleeId else session.callerId
                    signalingClient.sendHangUp(
                        sessionId = sid,
                        toDeviceId = toDeviceId
                    ).getOrThrow()
                }
            } catch (e: Exception) {
                // Best effort
            }
        }
    }

    /**
     * Enable or disable talk-back (half-duplex voice communication).
     *
     * @param enabled Whether talk-back is enabled
     */
    fun enableTalkBack(enabled: Boolean) {
        audioDeviceManager.enableTalkBack(enabled)
    }

    /**
     * Start the adaptive bitrate controller for the current peer connection.
     *
     * Monitors network quality and automatically adjusts video resolution and bitrate
     * based on available bandwidth. Called automatically when ICE connects.
     */
    private fun startAdaptiveBitrate() {
        val pc = peerConnectionManager.getPeerConnection() ?: return
        stopAdaptiveBitrate()

        adaptiveBitrateController = AdaptiveBitrateController(
            peerConnectionManager = peerConnectionManager,
            scope = scope,
            onVideoDisabled = {
                handler.post {
                    _isAudioOnly.value = true
                }
            },
            onVideoEnabled = {
                handler.post {
                    _isAudioOnly.value = false
                }
            }
        ).apply {
            start(cameraCaptureManager.currentRtpSender)
        }

        // Observe quality tier changes
        scope.launch {
            while (isActive) {
                val controller = adaptiveBitrateController ?: break
                _videoQuality.value = controller.currentQualityTier
                delay(5_000)
            }
        }
    }

    /**
     * Stop the adaptive bitrate controller and clean up its resources.
     */
    private fun stopAdaptiveBitrate() {
        adaptiveBitrateController?.stop()
        adaptiveBitrateController = null
    }

    /**
     * Switch between front and back camera.
     */
    fun switchCamera() {
        cameraCaptureManager.switchCamera()
    }

    /**
     * Toggle video on/off during a call.
     */
    fun toggleVideo(enabled: Boolean) {
        cameraCaptureManager.setVideoEnabled(enabled)
        _isAudioOnly.value = !enabled
    }

    /**
     * Toggle mute during a call.
     */
    fun toggleMute(muted: Boolean) {
        audioDeviceManager.setAudioEnabled(!muted)
    }

    /**
     * Set the remote session description (used when receiving an offer/answer).
     *
     * @param sdp The remote [SessionDescription].
     */
    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnectionManager.setRemoteDescription(sdp)
    }

    /**
     * Add a remote ICE candidate received from the signaling server.
     *
     * @param candidate The [IceCandidate] to add.
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnectionManager.addIceCandidate(candidate)
    }

    /**
     * Return the EglBase context used by WebRTC, or null if not initialized.
     */
    fun getEglBase(): org.webrtc.EglBase? = peerConnectionManager.getEglBase()

    /**
     * Clean up call-related resources (tracks, peer connection, bitrate controller).
     * The factory and EglBase are preserved so new calls can be started quickly.
     */
    fun cleanup() {
        stopAdaptiveBitrate()
        cameraCaptureManager.clearRtpSender()
        cameraCaptureManager.stopCapture()
        audioDeviceManager.stopAudioCapture()
        peerConnectionManager.closeConnection()
        _remoteVideoTrack.value = null
    }

    /**
     * Full cleanup of **all** WebRTC resources including the factory and EglBase.
     * Call this when the app is shutting down or the user signs out.
     * After calling this, [initializeWebRtc] must be invoked again before making calls.
     */
    fun fullCleanup() {
        cleanup()
        peerConnectionManager.disposeFactory()
    }

    private suspend fun getChildDeviceId(): String {
        return securePreferences.getString("device_id", "") ?: ""
    }

    /**
     * Create a [PeerConnection] with the standard ICE event listener used by this facade.
     *
     * @param iceServers STUN/TURN servers.
     * @return The created [PeerConnection], or null.
     */
    private fun createPeerConnectionWithListener(
        iceServers: List<PeerConnection.IceServer>
    ): org.webrtc.PeerConnection? {
        return peerConnectionManager.createPeerConnection(iceServers, object : WebRtcPeerConnectionManager.IceEventListener {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                scope.launch {
                    _connectionEvents.emit(ConnectionEvent.ConnectionStateChanged(state))
                }
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        scope.launch {
                            _callState.value = CallState.Connected(
                                _currentSession.value?.sessionId ?: ""
                            )
                            startAdaptiveBitrate()
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        scope.launch {
                            _callState.value = CallState.Error("Connection lost")
                            stopAdaptiveBitrate()
                        }
                    }
                    else -> {}
                }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    val session = _currentSession.value ?: return@launch
                    val childDeviceId = getChildDeviceId()
                    val toDeviceId = if (session.callerId == childDeviceId) session.calleeId else session.callerId
                    signalingClient.sendIceCandidate(
                        sessionId = session.sessionId,
                        toDeviceId = toDeviceId,
                        candidate = candidate
                    ).getOrThrow()
                }
            }

            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.let { track ->
                    handler.post { _remoteVideoTrack.value = track }
                }
            }

            override fun onRemoveStream(stream: MediaStream) {
                handler.post { _remoteVideoTrack.value = null }
            }

            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver?,
                streams: Array<out MediaStream>?
            ) {
                receiver?.track()?.let { track ->
                    if (track is VideoTrack) {
                        handler.post { _remoteVideoTrack.value = track }
                    }
                }
            }

            override fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?) {}
        })
    }

    /**
     * Adaptive bitrate controller that monitors network quality via WebRTC stats
     * and automatically adjusts video resolution and bitrate.
     *
     * Polls [PeerConnection.getStats] every 5 seconds to estimate available bandwidth,
     * then reconfigures the video [RtpSender] encoding parameters accordingly.
     *
     * Bandwidth-to-quality mapping:
     * - Good (> 10 Mbps): 720p at 15 fps, 1.5 Mbps target bitrate
     * - Moderate (2 - 10 Mbps): 480p at 10 fps, 800 Kbps target bitrate
     * - Poor (500 Kbps - 2 Mbps): 360p at 10 fps, 400 Kbps target bitrate (degraded video)
     * - Very poor (< 500 Kbps): Audio-only fallback (video disabled)
     *
     * @property peerConnectionManager The [WebRtcPeerConnectionManager] to monitor and control.
     * @property scope The coroutine scope for the stats polling loop.
     * @property onVideoDisabled Called when bandwidth drops below the video threshold.
     * @property onVideoEnabled Called when bandwidth recovers above the video threshold.
     */
    private class AdaptiveBitrateController(
        private val peerConnectionManager: WebRtcPeerConnectionManager,
        private val scope: CoroutineScope,
        private val onVideoDisabled: () -> Unit = {},
        private val onVideoEnabled: () -> Unit = {}
    ) {

        companion object {
            private const val TAG = "AdaptiveBitrate"
            private const val STATS_INTERVAL_MS = 5_000L

            // Bandwidth thresholds in Kbps
            private const val BW_GOOD_KBPS = 10_000L    // > 10 Mbps -> 720p
            private const val BW_MODERATE_KBPS = 2_000L // > 2 Mbps -> 480p
            private const val BW_POOR_KBPS = 500L       // > 500 Kbps -> 360p degraded
            // < 500 Kbps -> audio only

            // Target bitrates in bps
            private const val BITRATE_GOOD_BPS = 1_500_000   // 1.5 Mbps
            private const val BITRATE_MODERATE_BPS = 800_000  // 800 Kbps
            private const val BITRATE_POOR_BPS = 400_000      // 400 Kbps

            // Resolution scaling factors (applied to source resolution)
            private const val SCALE_FULL = 1.0
            private const val SCALE_HALF = 2.0   // 320x240
            private const val SCALE_THIRD = 3.0  // ~213x160
        }

        private var statsJob: Job? = null
        private var lastEstimatedKbps: Long = BW_GOOD_KBPS
        private var isVideoCurrentlyDisabled: Boolean = false
        private var videoSender: RtpSender? = null

        /** Current video quality tier for external observation. */
        val currentQualityTier: VideoQualityTier
            get() = bandwidthToQualityTier(lastEstimatedKbps)

        /**
         * Start the adaptive bitrate monitoring loop.
         *
         * @param videoRtpSender The [RtpSender] for the local video track.
         */
        fun start(videoRtpSender: RtpSender?) {
            this.videoSender = videoRtpSender

            if (statsJob != null) return

            statsJob = scope.launch(Dispatchers.Default) {
                // Allow initial connection to stabilize before adjusting
                delay(STATS_INTERVAL_MS)

                while (isActive) {
                    try {
                        collectStatsAndAdjust()
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Stats collection error", e)
                    }
                    delay(STATS_INTERVAL_MS)
                }
            }

            android.util.Log.i(TAG, "Adaptive bitrate controller started")
        }

        /**
         * Stop the adaptive bitrate monitoring loop.
         */
        fun stop() {
            statsJob?.cancel()
            statsJob = null
            videoSender = null
            android.util.Log.i(TAG, "Adaptive bitrate controller stopped")
        }

        /**
         * Collect connection statistics and adjust bitrate/resolution accordingly.
         */
        private suspend fun collectStatsAndAdjust() {
            val bandwidthKbps = estimateBandwidthKbps()
            lastEstimatedKbps = bandwidthKbps

            val tier = bandwidthToQualityTier(bandwidthKbps)
            android.util.Log.d(TAG, "Estimated bandwidth: ${bandwidthKbps}Kbps, tier: $tier")

            when (tier) {
                VideoQualityTier.HIGH -> applyHighQuality()
                VideoQualityTier.MEDIUM -> applyMediumQuality()
                VideoQualityTier.LOW -> applyLowQuality()
                VideoQualityTier.AUDIO_ONLY -> applyAudioOnly()
            }
        }

        /**
         * Apply high-quality settings: 1.5 Mbps bitrate, full resolution.
         */
        private fun applyHighQuality() {
            if (isVideoCurrentlyDisabled) {
                isVideoCurrentlyDisabled = false
                onVideoEnabled()
            }
            applyEncodingParams(
                maxBitrateBps = BITRATE_GOOD_BPS,
                scaleResolutionDownBy = SCALE_FULL
            )
        }

        /**
         * Apply medium-quality settings: 800 Kbps bitrate, half resolution.
         */
        private fun applyMediumQuality() {
            if (isVideoCurrentlyDisabled) {
                isVideoCurrentlyDisabled = false
                onVideoEnabled()
            }
            applyEncodingParams(
                maxBitrateBps = BITRATE_MODERATE_BPS,
                scaleResolutionDownBy = SCALE_HALF
            )
        }

        /**
         * Apply low-quality settings: 400 Kbps bitrate, third resolution.
         */
        private fun applyLowQuality() {
            if (isVideoCurrentlyDisabled) {
                isVideoCurrentlyDisabled = false
                onVideoEnabled()
            }
            applyEncodingParams(
                maxBitrateBps = BITRATE_POOR_BPS,
                scaleResolutionDownBy = SCALE_THIRD
            )
        }

        /**
         * Disable video entirely — audio-only fallback for very poor networks.
         */
        private fun applyAudioOnly() {
            if (!isVideoCurrentlyDisabled) {
                isVideoCurrentlyDisabled = true
                applyEncodingParams(maxBitrateBps = 0, scaleResolutionDownBy = SCALE_FULL)
                onVideoDisabled()
            }
        }

        /**
         * Apply encoding parameters to the video RtpSender.
         */
        private fun applyEncodingParams(maxBitrateBps: Int, scaleResolutionDownBy: Double) {
            val sender = videoSender ?: return

            try {
                val params = sender.parameters
                val encodings = params.encodings

                if (encodings.isNotEmpty()) {
                    val encoding = encodings[0]
                    encoding.maxBitrateBps = if (maxBitrateBps > 0) maxBitrateBps else null
                    encoding.minBitrateBps = null
                    encoding.scaleResolutionDownBy = if (maxBitrateBps > 0) scaleResolutionDownBy else null

                    when {
                        maxBitrateBps >= BITRATE_GOOD_BPS -> encoding.maxFramerate = 15
                        maxBitrateBps >= BITRATE_MODERATE_BPS -> encoding.maxFramerate = 10
                        maxBitrateBps >= BITRATE_POOR_BPS -> encoding.maxFramerate = 10
                        else -> encoding.maxFramerate = 0
                    }

                    val result = sender.setParameters(params)
                    if (!result) {
                        android.util.Log.w(TAG, "Failed to set encoding parameters")
                    } else {
                        android.util.Log.d(
                            TAG,
                            "Applied encoding: bitrate=$maxBitrateBps, scale=$scaleResolutionDownBy"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error applying encoding parameters", e)
            }
        }

        /**
         * Estimate available outgoing bandwidth from WebRTC connection statistics.
         */
        private suspend fun estimateBandwidthKbps(): Long {
            return try {
                val outgoingBitrate = getAvailableOutgoingBitrate()
                if (outgoingBitrate > 0) {
                    return outgoingBitrate / 1000
                }

                val estimatedFromBytes = estimateBandwidthFromBytesSent()
                if (estimatedFromBytes > 0) {
                    return estimatedFromBytes
                }

                BW_GOOD_KBPS
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Bandwidth estimation failed", e)
                lastEstimatedKbps
            }
        }

        /**
         * Get the `availableOutgoingBitrate` from ICE candidate-pair statistics.
         */
        private suspend fun getAvailableOutgoingBitrate(): Long {
            return peerConnectionManager.collectStats { report ->
                var bitrate: Long = 0
                val statsMap = report.statsMap
                for ((_, stats) in statsMap) {
                    if (stats.type == "candidate-pair" && stats.members["state"] == "succeeded") {
                        val availableBitrate = stats.members["availableOutgoingBitrate"] as? Number
                        if (availableBitrate != null && availableBitrate.toLong() > 0) {
                            bitrate = availableBitrate.toLong()
                            break
                        }
                    }
                }
                bitrate
            }
        }

        /**
         * Estimate bandwidth by tracking bytesSent deltas on outbound RTP streams.
         */
        private var previousBytesSent: Long = 0
        private var previousTimestampUs: Long = 0

        private suspend fun estimateBandwidthFromBytesSent(): Long {
            return peerConnectionManager.collectStats { report ->
                var totalBytesSent: Long = 0
                val statsMap = report.statsMap
                for ((_, stats) in statsMap) {
                    if (stats.type == "outbound-rtp") {
                        val bytesSent = stats.members["bytesSent"] as? Number
                        if (bytesSent != null) {
                            totalBytesSent += bytesSent.toLong()
                        }
                    }
                }

                val nowUs = System.nanoTime() / 1000
                val deltaBytes = totalBytesSent - previousBytesSent
                val deltaUs = nowUs - previousTimestampUs

                previousBytesSent = totalBytesSent
                previousTimestampUs = nowUs

                if (deltaUs > 0 && deltaBytes > 0) {
                    val bps = (deltaBytes * 8 * 1_000_000L) / deltaUs
                    bps / 1000
                } else {
                    0L
                }
            }
        }

        /**
         * Map an estimated bandwidth value to the corresponding quality tier.
         */
        private fun bandwidthToQualityTier(bandwidthKbps: Long): VideoQualityTier {
            return when {
                bandwidthKbps > BW_GOOD_KBPS -> VideoQualityTier.HIGH
                bandwidthKbps > BW_MODERATE_KBPS -> VideoQualityTier.MEDIUM
                bandwidthKbps > BW_POOR_KBPS -> VideoQualityTier.LOW
                else -> VideoQualityTier.AUDIO_ONLY
            }
        }
    }
}

/**
 * Video quality tiers corresponding to bandwidth estimates.
 */
enum class VideoQualityTier {
    /** High quality: 720p/15fps at 1.5 Mbps. */
    HIGH,

    /** Medium quality: 480p/10fps at 800 Kbps. */
    MEDIUM,

    /** Low quality: 360p/10fps at 400 Kbps. */
    LOW,

    /** Audio only: video disabled due to insufficient bandwidth. */
    AUDIO_ONLY
}

/**
 * Call state sealed class for UI consumption.
 */
sealed class CallState {
    data object Idle : CallState()
    data class Connecting(val sessionId: String) : CallState()
    data class Ringing(val sessionId: String) : CallState()
    data class Incoming(val sessionId: String, val callerName: String) : CallState()
    data class Connected(val sessionId: String) : CallState()
    data class Ended(val sessionId: String) : CallState()
    data class Error(val message: String) : CallState()
}

/**
 * Connection events for the UI.
 */
sealed class ConnectionEvent {
    data class RemoteVideoAvailable(val track: VideoTrack) : ConnectionEvent()
    data object RemoteVideoRemoved : ConnectionEvent()
    data class ConnectionStateChanged(val state: PeerConnection.IceConnectionState) : ConnectionEvent()
}
