package com.childhelper.core.network.notification

import android.util.Log
import com.childhelper.core.common.model.Alert
import com.childhelper.core.network.BuildConfig
import com.childhelper.core.common.notification.NotificationSender
import com.childhelper.core.network.api.SignalingApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [NotificationSender] that delivers alerts
 * to guardians via the backend's FCM notification endpoint.
 *
 * The alert is serialized to JSON (metadata only) and sent via HTTP POST
 * to the backend, which then forwards it to guardian devices through FCM.
 *
 * Includes retry logic with exponential backoff for transient failures.
 *
 * Privacy guarantee: Only [Alert] metadata fields are serialized and sent.
 * No raw audio, video, or image data is ever included in the payload.
 */
@Singleton
class FcmNotificationSender(
    private val signalingApi: SignalingApi
) : NotificationSender {

    companion object {
        private const val TAG = "FcmNotificationSender"
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    override suspend fun sendAlert(alert: Alert, isHighPriority: Boolean): Result<Unit> {
        var lastException: Throwable? = null
        var delayMs = INITIAL_RETRY_DELAY_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                // Build the metadata-only JSON payload
                val payload = buildJsonObject {
                    put("alertId", alert.id)
                    put("eventType", alert.eventType.name)
                    put("timestamp", alert.timestamp)
                    put("childDeviceId", alert.childDeviceId)
                    put("priority", if (isHighPriority) "high" else "normal")
                    alert.confidence?.let { put("confidence", it) }
                    put("batteryPercent", alert.deviceStatus.batteryPercent)
                    put("isCharging", alert.deviceStatus.isCharging)
                    put("networkType", alert.deviceStatus.networkType)
                    put("monitorMode", alert.deviceStatus.monitorMode.name)
                }

                // Send to backend notification endpoint
                // The SignalingApi has a generic sendNotification endpoint
                val response = signalingApi.sendNotification(
                    childDeviceId = alert.childDeviceId,
                    payload = payload
                )

                if (response.isSuccessful) {
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            TAG,
                            "Alert sent successfully: ${alert.eventType} " +
                            "(id=${alert.id}, highPriority=$isHighPriority)"
                        )
                    }
                    return Result.success(Unit)
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.w(
                        TAG,
                        "Notification request failed (${response.code()}): $errorBody"
                    )
                    lastException = HttpException(response)
                }
            } catch (e: IOException) {
                // Network issue — retry
                Log.w(TAG, "Network error sending alert (attempt ${attempt + 1}/$MAX_RETRIES)", e)
                lastException = e
            } catch (e: HttpException) {
                // HTTP error — retry if 5xx server error
                if (e.code() >= 500) {
                    Log.w(TAG, "Server error ${e.code()} (attempt ${attempt + 1}/$MAX_RETRIES)", e)
                    lastException = e
                } else {
                    // 4xx errors won't be fixed by retrying
                    Log.e(TAG, "Client error ${e.code()} — not retrying", e)
                    return Result.failure(e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error sending alert", e)
                lastException = e
            }

            // Wait before retry (except after the last attempt)
            if (attempt < MAX_RETRIES - 1) {
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2 // Exponential backoff
            }
        }

        // All retries exhausted
        val failure = lastException ?: RuntimeException("Failed to send alert after $MAX_RETRIES attempts")
        Log.e(TAG, "All retries exhausted for alert ${alert.id}", failure)
        return Result.failure(failure)
    }
}
