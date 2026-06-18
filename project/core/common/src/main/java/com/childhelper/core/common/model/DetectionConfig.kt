package com.childhelper.core.common.model

import kotlinx.serialization.Serializable

/**
 * Sensitivity level for ML-based detection pipelines.
 */
@Serializable
enum class SensitivityLevel {
    /** Fewer false positives but may miss some events. */
    LOW,

    /** Balanced detection — recommended for most use cases. */
    NORMAL,

    /** Maximum sensitivity; may produce more false positives. */
    HIGH
}

/**
 * Retention period for alert history stored locally on the parent device.
 *
 * Older alerts are automatically pruned to protect privacy and manage storage.
 */
@Serializable
enum class RetentionPeriod {
    /** No alert history is retained. */
    OFF,

    /** Keep alerts for the last 24 hours. */
    TWENTY_FOUR_HOURS,

    /** Keep alerts for the last 7 days. */
    SEVEN_DAYS
}

/**
 * Configuration for the on-device cry and motion detection pipelines.
 *
 * All thresholds and sensitivity values are tuned for bedroom/baby-room
 * environments. Settings are synced from the parent app to the child device.
 *
 * @property sensitivity Overall detection sensitivity level.
 * @property cryEnabled Whether cry detection is active.
 * @property motionEnabled Whether motion detection is active.
 * @property cryThreshold Minimum confidence (0.0–1.0) to trigger a cry alert.
 * @property motionThreshold Minimum confidence (0.0–1.0) to trigger a motion alert.
 * @property cryConsecutiveWindows Number of consecutive audio windows required.
 * @property motionConsecutiveFrames Number of consecutive camera frames required.
 * @property alertHistoryRetention How long to keep alert history on the parent device.
 */
@Serializable
data class DetectionConfig(
    val sensitivity: SensitivityLevel = SensitivityLevel.NORMAL,
    val cryEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val cryThreshold: Float = 0.7f,
    val motionThreshold: Float = 0.15f,
    val cryConsecutiveWindows: Int = 3,
    val motionConsecutiveFrames: Int = 2,
    val alertHistoryRetention: RetentionPeriod = RetentionPeriod.TWENTY_FOUR_HOURS
)
