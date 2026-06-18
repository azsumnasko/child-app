package com.childhelper.core.common.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SignalingMessage {
    abstract val messageId: String
    abstract val fromDeviceId: String
    abstract val toDeviceId: String
    abstract val timestamp: Long
    abstract val sessionId: String
}

@Serializable
@SerialName("sdp")
data class SdpMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val type: SdpType,
    val sdp: String
) : SignalingMessage()

@Serializable
enum class SdpType { OFFER, ANSWER }

@Serializable
@SerialName("ice")
data class IceMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val candidate: String,
    val sdpMLineIndex: Int,
    val sdpMid: String
) : SignalingMessage()

@Serializable
@SerialName("hangup")
data class HangUpMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val reason: HangUpReason = HangUpReason.USER_INITIATED
) : SignalingMessage()

@Serializable
enum class HangUpReason {
    USER_INITIATED, CONNECTION_ERROR, TIMEOUT, PEER_UNAVAILABLE, NETWORK_ERROR
}

@Serializable
@SerialName("ping")
data class PingMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String
) : SignalingMessage()

@Serializable
@SerialName("pong")
data class PongMessage(
    override val messageId: String,
    override val fromDeviceId: String,
    override val toDeviceId: String,
    override val timestamp: Long,
    override val sessionId: String,
    val rttMs: Long? = null
) : SignalingMessage()
