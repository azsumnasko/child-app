package com.childhelper.core.common.model

import kotlinx.serialization.Serializable

/**
 * User-configurable application settings stored encrypted on-device.
 *
 * Settings are synced between parent and child apps via encrypted push messages.
 * All settings respect the privacy-first design: no cloud storage, no telemetry.
 *
 * @property cryDetectionEnabled Whether the cry detection pipeline is active.
 * @property motionDetectionEnabled Whether the motion detection pipeline is active.
 * @property sensitivity Overall detection sensitivity for both pipelines.
 * @property bedtimeAutoAnswer Whether calls should auto-answer during bedtime mode.
 * @property alertHistoryRetention How long alert history is kept on the parent device.
 * @property sosEscalationOrder Ordered list of contact IDs for SOS escalation.
 * @property locationSharingEnabled Whether SOS events include GPS location (default: off).
 * @property pushNotificationsEnabled Whether FCM push notifications are enabled.
 */
@Serializable
data class AppSettings(
    val cryDetectionEnabled: Boolean = true,
    val motionDetectionEnabled: Boolean = true,
    val sensitivity: SensitivityLevel = SensitivityLevel.NORMAL,
    val bedtimeAutoAnswer: Boolean = true,
    val alertHistoryRetention: RetentionPeriod = RetentionPeriod.TWENTY_FOUR_HOURS,
    val sosEscalationOrder: List<String> = emptyList(),
    val locationSharingEnabled: Boolean = false,
    val pushNotificationsEnabled: Boolean = true
)
