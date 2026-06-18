package com.childhelper.core.common.model

import kotlinx.serialization.Serializable

/**
 * Operating modes for the child device's monitoring subsystem.
 */
@Serializable
enum class MonitorMode {
    /** No active monitoring; device is idle. */
    IDLE,

    /** Bedtime mode active — cry and motion detection running with auto-answer calls. */
    BEDTIME,

    /** A voice/video call is currently in progress. */
    CALLING,

    /** SOS alert is active; emergency escalation in progress. */
    SOS
}

/**
 * Real-time status of a child device as reported to the parent dashboard.
 *
 * This model contains only non-sensitive telemetry (battery, network, mode).
 * No location or media data is included.
 *
 * @property deviceId Unique stable identifier for the child device.
 * @property isOnline Whether the device is currently connected and reporting.
 * @property batteryPercent Current battery level (0–100).
 * @property isCharging Whether the device is plugged into power.
 * @property networkType Active network type: `"wifi"`, `"cellular"`, or `"none"`.
 * @property monitorMode Current monitoring operating mode.
 * @property lastSeen Epoch millis of the last heartbeat received.
 */
@Serializable
data class DeviceStatus(
    val deviceId: String,
    val isOnline: Boolean = true,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String, // "wifi" | "cellular" | "none"
    val monitorMode: MonitorMode = MonitorMode.IDLE,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Immutable snapshot of device status captured at the time an alert is generated.
 *
 * This is embedded in [Alert] records to preserve historical context without
 * referencing mutable live state.
 *
 * @property batteryPercent Battery level (0–100) at snapshot time.
 * @property isCharging Whether the device was charging at snapshot time.
 * @property networkType Network type at snapshot time.
 * @property monitorMode Monitor mode at snapshot time.
 */
@Serializable
data class DeviceStatusSnapshot(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String,
    val monitorMode: MonitorMode
)
