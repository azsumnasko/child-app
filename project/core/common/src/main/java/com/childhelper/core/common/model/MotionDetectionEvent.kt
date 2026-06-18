package com.childhelper.core.common.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Event emitted when the motion detection pipeline identifies significant movement.
 *
 * Motion detection runs locally on-device using TensorFlow Lite on CameraX
 * ImageAnalysis frames. Camera frames are discarded immediately after inference.
 * Only this metadata event is emitted and optionally forwarded to parents.
 *
 * **Privacy:** No images or video recordings are stored or transmitted. Only
 * inference metadata (confidence, timing) leaves the device.
 *
 * @property id Unique identifier for this detection event (UUID).
 * @property timestamp Epoch millis when the motion was detected.
 * @property confidence Model confidence score (0.0–1.0). Higher is more certain.
 * @property consecutiveFrames Number of consecutive camera frames that triggered.
 * @property childDeviceId The device ID that generated this event.
 */
@Serializable
data class MotionDetectionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float,
    val consecutiveFrames: Int,
    val childDeviceId: String
)
