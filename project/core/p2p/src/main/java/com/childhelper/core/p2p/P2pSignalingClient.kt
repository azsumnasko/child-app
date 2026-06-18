package com.childhelper.core.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class P2pSignalingClient(
    private val p2pManager: LocalP2pManager,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _sdpOfferFlow = MutableSharedFlow<SdpPayload>(extraBufferCapacity = 8)
    val sdpOfferFlow: SharedFlow<SdpPayload> = _sdpOfferFlow.asSharedFlow()

    private val _sdpAnswerFlow = MutableSharedFlow<SdpPayload>(extraBufferCapacity = 8)
    val sdpAnswerFlow: SharedFlow<SdpPayload> = _sdpAnswerFlow.asSharedFlow()

    private val _iceCandidateFlow = MutableSharedFlow<IcePayload>(extraBufferCapacity = 32)
    val iceCandidateFlow: SharedFlow<IcePayload> = _iceCandidateFlow.asSharedFlow()

    private val _hangUpFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val hangUpFlow: SharedFlow<String> = _hangUpFlow.asSharedFlow()

    init {
        scope.launch {
            p2pManager.messageFlow.collect { message ->
                when (message.type) {
                    P2pMessageType.SIGNAL_OFFER -> {
                        _sdpOfferFlow.emit(json.decodeFromString<SdpPayload>(message.payload))
                    }
                    P2pMessageType.SIGNAL_ANSWER -> {
                        _sdpAnswerFlow.emit(json.decodeFromString<SdpPayload>(message.payload))
                    }
                    P2pMessageType.SIGNAL_ICE -> {
                        _iceCandidateFlow.emit(json.decodeFromString<IcePayload>(message.payload))
                    }
                    P2pMessageType.HANG_UP -> {
                        _hangUpFlow.emit(message.payload)
                    }
                    else -> {}
                }
            }
        }
    }

    suspend fun sendOffer(sessionId: String, sdp: String, targetDeviceId: String = "") {
        val payload = json.encodeToString(SdpPayload(sessionId, "offer", sdp, targetDeviceId))
        p2pManager.sendMessage(P2pMessage(type = P2pMessageType.SIGNAL_OFFER, payload = payload))
    }

    suspend fun sendAnswer(sessionId: String, sdp: String, targetDeviceId: String = "") {
        val payload = json.encodeToString(SdpPayload(sessionId, "answer", sdp, targetDeviceId))
        p2pManager.sendMessage(P2pMessage(type = P2pMessageType.SIGNAL_ANSWER, payload = payload))
    }

    suspend fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val payload = json.encodeToString(IcePayload(sdpMid, sdpMLineIndex, candidate))
        p2pManager.sendMessage(P2pMessage(type = P2pMessageType.SIGNAL_ICE, payload = payload))
    }

    suspend fun sendHangUp(reason: String = "user") {
        p2pManager.sendMessage(P2pMessage(type = P2pMessageType.HANG_UP, payload = reason))
    }

    companion object { private const val TAG = "P2pSignalingClient" }
}
