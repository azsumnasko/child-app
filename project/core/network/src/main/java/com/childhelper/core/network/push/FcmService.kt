package com.childhelper.core.network.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.model.AlertType
import com.childhelper.core.common.model.DeviceStatusSnapshot
import com.childhelper.core.common.model.MonitorMode
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.signaling.WebRtcSignalingClient
import com.childhelper.core.security.SecurePreferences
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service that receives push notifications
 * and emits parsed [Alert] objects for the app to consume.
 *
 * **Privacy guarantee**: FCM payloads contain ONLY metadata — event type,
 * timestamp, and device status. NO audio, video, or image data is ever
 * transmitted through Firebase. All media flows peer-to-peer via WebRTC.
 *
 * The service handles:
 * - Cry detection alerts (child device detected crying)
 * - Motion detection alerts (child device detected motion)
 * - SOS activation alerts (child pressed emergency button)
 * - Camera obstruction alerts (child camera is blocked)
 * - Device offline / low battery alerts
 * - Call signaling notifications (incoming call, call ended)
 * - Push-triggered signaling polls (new WebRTC signaling messages available)
 *
 * @see [Alert] for the data model of emitted alerts.
 * @see [WebRtcSignalingClient.pollNow] for the signaling poll triggered by push.
 */
@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {

    /**
     * Injected signaling client for triggering polls when push notifications
     * indicate new signaling messages are available.
     */
    @Inject
    lateinit var signalingClient: WebRtcSignalingClient

    @Inject
    lateinit var pairingApi: PairingApi

    @Inject
    lateinit var securePreferences: SecurePreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch {
            try {
                val deviceId = securePreferences.getString("device_id") ?: return@launch
                val payload = buildJsonObject {
                    put("deviceId", deviceId)
                    put("fcmToken", token)
                }
                pairingApi.registerFcmToken(payload)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register FCM token with backend", e)
            }
        }
    }

    /**
     * Called when a new FCM message is received while the app is in the foreground.
     *
     * Parses the message payload into an [Alert] object and emits it via
     * [alertFlow]. If the message is a signaling trigger, it initiates
     * an immediate poll via [WebRtcSignalingClient.pollNow].
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        if (data.isEmpty()) return

        // Handle signaling push triggers — these tell us to poll for new
        // WebRTC signaling messages rather than carrying the messages themselves.
        if (data["type"] == "signal_poll") {
            serviceScope.launch {
                runCatching {
                    signalingClient.pollNow()
                }
            }
            return
        }

        // Parse and emit alert notifications
        val alert = parseAlert(data)
        if (alert != null) {
            serviceScope.launch {
                _alertFlow.emit(alert)
            }
        }
    }

    /**
     * Parses FCM message data into an [Alert] object.
     *
     * Expected data keys:
     * - `eventType`: One of the [AlertType] enum names.
     * - `alertId`: Unique identifier for this alert event.
     * - `timestamp`: Unix timestamp in milliseconds.
     * - `confidence`: Optional float confidence score (0.0 - 1.0).
     * - `childDeviceId`: The device ID of the child device that generated the alert.
     * - `batteryPercent`: Current battery level (0-100).
     * - `isCharging`: "true" or "false".
     * - `networkType": "wifi", "cellular", or "none".
     * - `monitorMode`: One of the [MonitorMode] enum names.
     *
     * @param data The FCM message data payload.
     * @return Parsed [Alert] or null if the data is malformed or incomplete.
     */
    private fun parseAlert(data: Map<String, String>): Alert? {
        return try {
            val eventType = data["eventType"]
                ?.let { AlertType.valueOf(it) }
                ?: return null

            val alertId = data["alertId"] ?: generateAlertId()
            val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
            val confidence = data["confidence"]?.toFloatOrNull()
            val childDeviceId = data["childDeviceId"] ?: return null

            val deviceStatus = DeviceStatusSnapshot(
                batteryPercent = data["batteryPercent"]?.toIntOrNull() ?: -1,
                isCharging = data["isCharging"] == "true",
                networkType = data["networkType"] ?: "unknown",
                monitorMode = data["monitorMode"]
                    ?.let { MonitorMode.valueOf(it) }
                    ?: MonitorMode.IDLE
            )

            Alert(
                id = alertId,
                eventType = eventType,
                timestamp = timestamp,
                confidence = confidence,
                deviceStatus = deviceStatus,
                childDeviceId = childDeviceId
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid AlertType or MonitorMode in FCM payload: ${data["eventType"]}", e)
            null
        } catch (e: Exception) {
            // Malformed payload — silently drop to avoid crashing the service.
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel("child_alerts", "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alert notifications from child device"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel("child_calls", "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming call notifications"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel("child_status", "Status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Device status updates"
                }
            )
        }
    }

    companion object {
        private const val TAG = "FcmService"
        private val _alertFlow = MutableSharedFlow<Alert>(extraBufferCapacity = 64)

        /**
         * SharedFlow of alerts received from FCM push notifications.
         *
         * Collect this flow in ViewModels or repositories to react to
         * real-time events from the child device.
         *
         * Example:
         * ```
         * viewModelScope.launch {
         *     FcmService.alertFlow.collect { alert ->
         *         when (alert.eventType) {
         *             AlertType.CRY_DETECTED -> showCryNotification(alert)
         *             AlertType.SOS_ACTIVATED -> showSosDialog(alert)
         *             else -> logAlert(alert)
         *         }
         *     }
         * }
         * ```
         */
        val alertFlow: SharedFlow<Alert> = _alertFlow.asSharedFlow()

        /**
         * Generates a unique alert ID using a cryptographically secure random source.
         *
         * Uses [UUID.randomUUID] which delegates to [java.security.SecureRandom]
         * for its entropy. This prevents predictability and enumeration attacks
         * that would be possible with [Math.random] (CWE-338).
         *
         * Format: `alert-<timestamp>-<random-uuid>`
         */
        private fun generateAlertId(): String =
            "alert-${System.currentTimeMillis()}-${UUID.randomUUID()}"

        /**
         * Internal emitter for testing purposes.
         * Not for production use — alerts should only come from FCM.
         */
        internal suspend fun emitTestAlert(alert: Alert) {
            _alertFlow.emit(alert)
        }
    }
}
