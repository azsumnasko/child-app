package com.childhelper.core.p2p

import kotlinx.serialization.Serializable

/** Generic P2P message envelope */
@Serializable
data class P2pMessage(
    val type: P2pMessageType,
    val payload: String,
    val messageId: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
enum class P2pMessageType {
    SIGNAL_OFFER, SIGNAL_ANSWER, SIGNAL_ICE, HANG_UP,
    ALERT_CRY, ALERT_MOTION, ALERT_SOS, ALERT_CAMERA,
    ALERT_BATTERY, STATUS_UPDATE, PING, PONG
}

@Serializable
data class SdpPayload(
    val sessionId: String, val type: String,
    val sdp: String, val targetDeviceId: String = ""
)

@Serializable
data class IcePayload(
    val sdpMid: String, val sdpMLineIndex: Int, val candidate: String
)

@Serializable
data class AlertPayload(
    val alertId: String, val eventType: String, val timestamp: Long,
    val confidence: Float?, val childDeviceId: String,
    val batteryPercent: Int, val isCharging: Boolean,
    val networkType: String, val monitorMode: String
)
