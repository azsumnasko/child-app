package com.childhelper.core.common.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Lifecycle states of an encrypted voice/video call between parent and child.
 */
@Serializable
enum class CallStatus {
    /** Call is being set up; WebRTC peer connection initializing. */
    CONNECTING,

    /** Remote device is being alerted (ringing). */
    RINGING,

    /** Call is active; media flowing bidirectionally. */
    CONNECTED,

    /** Call was ended normally by either party. */
    ENDED,

    /** Call failed to establish (network error, timeout, etc.). */
    FAILED
}

/**
 * Represents an encrypted voice/video call session between parent and child devices.
 *
 * All calls use WebRTC with end-to-end encryption via the shared secret established
 * during device pairing. No call data passes through any server unencrypted.
 *
 * **Privacy:** Calls are peer-to-peer encrypted. No cloud recording or storage.
 *
 * @property sessionId Unique identifier for this call session (UUID).
 * @property callerId Device ID of the caller.
 * @property calleeId Device ID of the callee.
 * @property status Current state of the call lifecycle.
 * @property startTime Epoch millis when the call connected (null until connected).
 * @property endTime Epoch millis when the call ended (null until ended).
 * @property isAutoAnswer Whether the call should auto-answer (bedtime mode on child device).
 * @property hasVideo Whether this call includes a video stream.
 */
@Serializable
data class CallSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val callerId: String,
    val calleeId: String,
    val status: CallStatus = CallStatus.CONNECTING,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val isAutoAnswer: Boolean = false,
    val hasVideo: Boolean = true
)
