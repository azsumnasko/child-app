package com.childhelper.core.common.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Event emitted when the cry detection ML model identifies a probable cry.
 *
 * Detection runs locally on-device using TensorFlow Lite. Raw audio buffers are
 * discarded immediately after inference. Only this metadata event is emitted and
 * optionally forwarded to paired parent devices.
 *
 * **Privacy:** No audio recordings are stored or transmitted. Only inference
 * metadata (confidence, timing) leaves the device.
 *
 * @property id Unique identifier for this detection event (UUID).
 * @property timestamp Epoch millis when the cry was detected.
 * @property confidence Model confidence score (0.0–1.0). Higher is more certain.
 * @property consecutiveWindows Number of consecutive analysis windows that triggered.
 * @property childDeviceId The device ID that generated this event.
 */
@Serializable
data class CryDetectionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float,
    val consecutiveWindows: Int,
    val childDeviceId: String
)
