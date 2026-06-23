package com.childhelper.core.network.signaling

import android.util.Log
import com.childhelper.core.network.BuildConfig
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
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcSignalingClient @Inject constructor(
    private val signalingApi: SignalingApi,
    private val deviceIdProvider: DeviceIdProvider,
    private val okHttpClient: OkHttpClient
) {

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingMessages: Flow<SignalingMessage> = _incomingMessages.asSharedFlow()

    val incomingOffers: Flow<SdpMessage>
        get() = _incomingMessages
            .filter { it is SdpMessage && it.type == SdpType.OFFER }
            .map { it as SdpMessage }

    val incomingAnswers: Flow<SdpMessage>
        get() = _incomingMessages
            .filter { it is SdpMessage && it.type == SdpType.ANSWER }
            .map { it as SdpMessage }

    val incomingIceCandidates: Flow<IceMessage>
        get() = _incomingMessages
            .filter { it is IceMessage }
            .map { it as IceMessage }

    val incomingHangUps: Flow<HangUpMessage>
        get() = _incomingMessages
            .filter { it is HangUpMessage }
            .map { it as HangUpMessage }

    private var pollingJob: Job? = null

    val isPolling: Boolean
        get() = pollingJob?.isActive == true

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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var webSocket: WebSocket? = null

    fun startPolling(intervalMs: Long = POLL_INTERVAL_MS) {
        if (webSocket != null || pollingJob?.isActive == true) return

        val deviceId = deviceIdProvider()
        if (deviceId.isBlank()) return

        val wsUrl = BuildConfig.API_BASE_URL
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/api/v1/signal/ws/$deviceId"

        val request = Request.Builder().url(wsUrl).build()
        pollingJob = clientScope.launch {
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected: $wsUrl")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = json.decodeFromString(SignalingMessage.serializer(), text)
                        _incomingMessages.tryEmit(msg)
                    } catch (e: Exception) {
                        Log.w(TAG, "WS deserialize failed: ${e.message}")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $code $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WebSocket failure, falling back to polling", t)
                    this@WebRtcSignalingClient.webSocket = null
                    startHttpPolling(intervalMs)
                }
            })
        }
    }

    private fun startHttpPolling(intervalMs: Long) {
        if (isPolling) return
        pollingJob = clientScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val success = runCatching {
                    val deviceId = deviceIdProvider()
                    if (deviceId.isNotBlank()) {
                        val messages = signalingApi.getPendingMessages(deviceId)
                        messages.forEach { raw ->
                            try {
                                val msg = json.decodeFromString(SignalingMessage.serializer(), raw.toString())
                                _incomingMessages.tryEmit(msg)
                            } catch (e: Exception) {
                                Log.w(TAG, "Deserialize fail: ${raw.toString().take(100)}", e)
                            }
                        }
                    }
                }.onFailure { e ->
                    consecutiveFailures++
                    Log.w(TAG, "Poll error (attempt $consecutiveFailures)", e)
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

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        webSocket?.close(1000, "stopped")
        webSocket = null
    }

    suspend fun pollNow(): List<SignalingMessage> = pollNowInternal(null)

    suspend fun pollNowWithErrorLog(onError: (String) -> Unit): List<SignalingMessage> = pollNowInternal(onError)

    private suspend fun pollNowInternal(onError: ((String) -> Unit)?): List<SignalingMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceId = deviceIdProvider()
            if (deviceId.isBlank()) return@withContext emptyList()
            val messages = signalingApi.getPendingMessages(deviceId)
            val parsed = mutableListOf<SignalingMessage>()
            messages.forEach { raw ->
                try {
                    val rawStr = raw.toString()
                    val msg = json.decodeFromString(SignalingMessage.serializer(), rawStr)
                    _incomingMessages.tryEmit(msg)
                    parsed.add(msg)
                } catch (e: Exception) {
                    val rawStr = raw.toString().take(200)
                    val errMsg = "pollNow deserialize FAILED: ${e.message} - raw: $rawStr"
                    Log.w(TAG, errMsg, e)
                    onError?.invoke(errMsg)
                }
            }
            parsed
        }.getOrDefault(emptyList())
    }

    fun shutdown() {
        stopPolling()
        clientScope.cancel()
    }

    private fun generateMessageId(): String =
        "sig-${UUID.randomUUID()}"

    companion object {
        private const val TAG = "WebRtcSignalingClient"
        const val POLL_INTERVAL_MS = 2000L
    }
}
