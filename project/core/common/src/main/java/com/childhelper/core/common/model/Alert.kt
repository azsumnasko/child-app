package com.childhelper.core.common.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Enumeration of all possible alert event types in the system.
 *
 * Alerts are metadata-only — they contain no audio, video, or image data.
 * This preserves child privacy while keeping parents informed.
 */
@Serializable
enum class AlertType {
    /** Cry detection model triggered above configured threshold. */
    CRY_DETECTED,

    /** Motion detection model triggered above configured threshold. */
    MOTION_DETECTED,

    /** Child manually activated SOS alert. */
    SOS_ACTIVATED,

    /** Camera lens is physically obstructed (covered or blocked). */
    CAMERA_OBSTRUCTED,

    /** Child device has gone offline and missed its heartbeat. */
    DEVICE_OFFLINE,

    /** Child device battery fell below a warning threshold. */
    LOW_BATTERY,

    /** A voice call was started between parent and child devices. */
    CALL_STARTED,

    /** A voice call was ended between parent and child devices. */
    CALL_ENDED,

    /** Device temperature is elevated (warm threshold exceeded). */
    THERMAL_WARNING,

    /** Device is critically overheating — monitoring stopped for safety. */
    DEVICE_OVERHEATING
}

/**
 * Metadata-only alert representing a significant event on the child device.
 *
 * **Privacy Guarantee:** This model contains **zero** audio, video, or image data.
 * Only event type, timestamp, model confidence, and device status snapshot are
 * transmitted and stored. Raw sensor data is discarded immediately after analysis.
 *
 * @property id Unique identifier for this alert (UUID).
 * @property eventType Classification of the alert event.
 * @property timestamp Epoch millis when the event occurred.
 * @property confidence Optional ML model confidence score (0.0–1.0), if applicable.
 * @property deviceStatus Snapshot of the child device status at event time.
 * @property childDeviceId The device ID of the child device that generated this alert.
 */
@Serializable
data class Alert(
    val id: String = UUID.randomUUID().toString(),
    val eventType: AlertType,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float? = null,
    val deviceStatus: DeviceStatusSnapshot,
    val childDeviceId: String
)
