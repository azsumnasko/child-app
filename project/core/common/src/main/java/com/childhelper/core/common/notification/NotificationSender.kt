package com.childhelper.core.common.notification

import com.childhelper.core.common.model.Alert

/**
 * Interface for sending alerts to guardians.
 *
 * Implementations are responsible for delivering metadata-only alerts
 * to parent/guardian devices via the appropriate transport mechanism
 * (FCM, HTTP POST to backend, etc.)
 *
 * Privacy guarantee: Only [Alert] metadata is sent. No raw audio,
 * video, or image data is ever transmitted.
 */
interface NotificationSender {

    /**
     * Send an alert to all registered guardians.
     *
     * @param alert The metadata-only alert to send
     * @param isHighPriority Whether to send as high-priority (for SOS events)
     * @return [Result] indicating success or failure
     */
    suspend fun sendAlert(alert: Alert, isHighPriority: Boolean = false): Result<Unit>
}
