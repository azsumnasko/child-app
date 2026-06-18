package com.childhelper.app.parent.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.childhelper.core.common.model.MonitorMode

/**
 * Room entity representing an alert history entry.
 *
 * PRIVACY CONSTRAINT: This entity stores ONLY metadata fields.
 * NO audio data, NO video frames, NO media thumbnails, NO raw buffers.
 * Only event classification, timestamp, confidence score, and device status snapshot.
 */
@Entity(
    tableName = "alerts",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["eventType"]),
        Index(value = ["childDeviceId"])
    ]
)
data class AlertEntity(
    @PrimaryKey
    val id: String,

    /** Alert classification type (e.g., CRY_DETECTED, MOTION_DETECTED) */
    val eventType: String,

    /** Unix timestamp in milliseconds */
    val timestamp: Long,

    /** ML model confidence score (0.0 - 1.0), nullable for non-ML events */
    val confidence: Float?,

    /** Child device identifier */
    val childDeviceId: String,

    // --- Device status snapshot (at time of alert) ---

    /** Battery percentage 0-100 at time of alert */
    val batteryPercent: Int,

    /** Whether device was charging at time of alert */
    val isCharging: Boolean,

    /** Network type: "wifi", "cellular", or "none" */
    val networkType: String,

    /** Monitor mode at time of alert */
    val monitorMode: String
) {
    companion object {
        fun fromAlertModel(alert: com.childhelper.core.common.model.Alert): AlertEntity =
            AlertEntity(
                id = alert.id,
                eventType = alert.eventType.name,
                timestamp = alert.timestamp,
                confidence = alert.confidence,
                childDeviceId = alert.childDeviceId,
                batteryPercent = alert.deviceStatus.batteryPercent,
                isCharging = alert.deviceStatus.isCharging,
                networkType = alert.deviceStatus.networkType,
                monitorMode = alert.deviceStatus.monitorMode.name
            )
    }

    /** Convert back to domain model for UI consumption */
    fun toDeviceStatusSnapshot(): com.childhelper.core.common.model.DeviceStatusSnapshot =
        com.childhelper.core.common.model.DeviceStatusSnapshot(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            networkType = networkType,
            monitorMode = MonitorMode.valueOf(monitorMode)
        )
}
