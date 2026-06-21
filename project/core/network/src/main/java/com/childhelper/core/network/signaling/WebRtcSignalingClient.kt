package com.childhelper.core.network.signaling

import android.util.Log
import com.childhelper.core.network.api.SignalingApi
import com.childhelper.core.network.di.DeviceIdProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import retrofit2.HttpException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WebRTC signaling message exchange between peer devices.
 *
 * This client handles the full signaling lifecycle: sending SDP offers/answers,
 * exchanging ICE candidates, and processing incoming signaling messages via
 * a polled or push-triggered mechanism. All operations are suspending functions
 * that properly handle cancellation and errors.
 *
 * **Privacy guarantee**: This class transmits ONLY SDP and ICE metadata.
 * No audio, video, or other media payload ever passes through signaling.
 *
 * @property signalingApi The Retrofit API for sending/receiving signaling messages.
 * @property deviceIdProvider [DeviceIdProvider] that returns the local device ID for message addressing.
 */
@Singleton
class WebRtcSignalingClient @Inject constructor(
    private val signalingApi: SignalingApi,
    private val deviceIdProvider: DeviceIdProvider
) {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * SharedFlow of incoming [SignalingMessage]s from the peer device.
     * Consumers collect this flow to react to offers, answers, and ICE candidates.
     */
    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingMessages: Flow<SignalingMessage> = _incomingMessages.asSharedFlow()

    /** Flow filtered to only SDP offer messages. */
    val incomingOffers: Flow<SdpMessage>
        get() = _incomingMessages
            .filter { it is SdpMessage && it.type == SdpType.OFFER }
            .map { it as SdpMessage }

    /** Flow filtered to only SDP answer messages. */
    val incomingAnswers: Flow<SdpMessage>
        get() = _incomingMessages
            .filter { it is SdpMessage && it.type == SdpType.ANSWER }
            .map { it as SdpMessage }

    /** Flow filtered to only ICE candidate messages. */
    val incomingIceCandidates: Flow<IceMessage>
        get() = _incomingMessages
            .filter { it is IceMessage }
            .map { it as IceMessage }

    /** Flow filtered to only hang-up messages. */
    val incomingHangUps: Flow<HangUpMessage>
        get() = _incomingMessages
            .filter { it is HangUpMessage }
            .map { it as HangUpMessage }

    /** Active polling job, if any. */
    private var pollingJob: Job? = null

    /** Whether the client is currently polling for messages. */
    val isPolling: Boolean
        get() = pollingJob?.isActive == true

    /**
     * Sends an SDP offer to the specified peer device.
     *
     * @param sessionId The call session identifier.
     * @param toDeviceId The peer device to receive the offer.
     * @param sessionDescription The WebRTC offer session description.
     * @return [Result] indicating success or failure.
     */
    suspend fun sendOffer(
        sessionId: String,
        toDeviceId: String,
        sessionDescription: SessionDescription
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val offer = SdpMessage(
                messageId = generateMessageId(),
                fromDeviceId = deviceIdProvider(),
                toDeviceId = toDeviceId,
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
                type = SdpType.OFFER,
                sdp = sessionDescription.description
            )
            signalingApi.sendOffer(offer)
        }
    }

    /**
     * Sends an SDP answer to the specified peer device.
     *
     * @param sessionId The call session identifier.
     * @param toDeviceId The peer device (the original offerer) to receive the answer.
     * @param sessionDescription The WebRTC answer session description.
     * @return [Result] indicating success or failure.
     */
    suspend fun sendAnswer(
        sessionId: String,
        toDeviceId: String,
        sessionDescription: SessionDescription
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val answer = SdpMessage(
                messageId = generateMessageId(),
                fromDeviceId = deviceIdProvider(),
                toDeviceId = toDeviceId,
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
                type = SdpType.ANSWER,
                sdp = sessionDescription.description
            )
            signalingApi.sendAnswer(answer)
        }
    }

    /**
     * Sends an ICE candidate to the peer device.
     *
     * Multiple candidates are typically sent per session as the ICE agent
     * discovers viable network paths.
     *
     * @param sessionId The call session identifier.
     * @param toDeviceId The peer device to receive the candidate.
     * @param candidate The WebRTC ICE candidate.
     * @return [Result] indicating success or failure.
     */
    suspend fun sendIceCandidate(
        sessionId: String,
        toDeviceId: String,
        candidate: IceCandidate
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val iceMessage = IceMessage(
                messageId = generateMessageId(),
                fromDeviceId = deviceIdProvider(),
                toDeviceId = toDeviceId,
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
                candidate = candidate.sdp,
                sdpMLineIndex = candidate.sdpMLineIndex,
                sdpMid = candidate.sdpMid.orEmpty()
            )
            signalingApi.sendIceCandidate(iceMessage)
        }
    }

    /**
     * Sends a hang-up signal to terminate the call session.
     *
     * @param sessionId The call session to terminate.
     * @param toDeviceId The peer device to notify.
     * @param reason The reason for hanging up.
     * @return [Result] indicating success or failure.
     */
    suspend fun sendHangUp(
        sessionId: String,
        toDeviceId: String,
        reason: HangUpReason = HangUpReason.USER_INITIATED
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val hangUpPayload = buildJsonObject {
                put("type", "hangup")
                put("sessionId", sessionId)
                put("fromDeviceId", deviceIdProvider())
                put("reason", reason.name)
                put("timestamp", System.currentTimeMillis())
            }
            signalingApi.sendNotification(toDeviceId, hangUpPayload)
            Unit
        }
    }

    /**
     * Starts polling for incoming signaling messages from the server.
     *
     * Polling runs on [Dispatchers.IO] and can be cancelled by calling [stopPolling].
     * Collected messages are emitted on [_incomingMessages] for consumers.
     *
     * @param intervalMs Polling interval in milliseconds (default: 2000ms).
     */
    fun startPolling(intervalMs: Long = POLL_INTERVAL_MS) {
        if (isPolling) return

        pollingJob = clientScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val success = runCatching {
                    val deviceId = deviceIdProvider()
                    if (deviceId.isNotBlank()) {
                        val messages = signalingApi.getPendingMessages(deviceId)
                        messages.forEach { message ->
                            _incomingMessages.tryEmit(message)
                        }
                    }
                }.onFailure { e ->
                    consecutiveFailures++
                    when {
                        e is HttpException && e.code() in setOf(401, 403, 404) ->
                            Log.w(TAG, "Permanent polling error HTTP ${e.code()} (attempt $consecutiveFailures)")
                        else ->
                            Log.w(TAG, "Transient polling error (attempt $consecutiveFailures)", e)
                    }
                }.isSuccess

                if (success) {
                    consecutiveFailures = 0
                    delay(intervalMs)
                } else {
                    val backoffMs = minOf(2000L * (1L shl (consecutiveFailures - 1).coerceAtMost(4)), 30000L)
                    delay(backoffMs)
                }
            }
        }
    }

    /**
     * Stops the polling loop.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Triggers an immediate poll for pending messages, bypassing the interval.
     * Useful when a push notification signals that new messages are available.
     *
     * @return The list of pending messages, or empty list on error.
     */
    suspend fun pollNow(): List<SignalingMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = deviceIdProvider()
            if (deviceId.isBlank()) return@withContext emptyList()
            val messages = signalingApi.getPendingMessages(deviceId)
            messages.forEach { message ->
                _incomingMessages.tryEmit(message)
            }
            messages
        }.getOrDefault(emptyList())
    }

    /**
     * Shuts down the signaling client, cancelling all coroutines.
     *
     * After calling this method, the client must not be reused.
     * A new instance should be created if signaling is needed again.
     */
    fun shutdown() {
        stopPolling()
        clientScope.cancel()
    }

    private fun generateMessageId(): String =
        "sig-${UUID.randomUUID()}"

    companion object {
        private const val TAG = "WebRtcSignalingClient"
        /** Default polling interval in milliseconds. */
        const val POLL_INTERVAL_MS = 2000L
    }
}

/** Extension to map Flow<SignalingMessage> to specific types safely. */
@Suppress("UNCHECKED_CAST")
private fun <T : SignalingMessage> Flow<T>.mapSignal(transform: suspend (T) -> T): Flow<T> =
    this.map { transform(it) }
