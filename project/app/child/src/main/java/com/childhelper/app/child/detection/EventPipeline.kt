package com.childhelper.app.child.detection

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.childhelper.app.child.BuildConfig
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.model.AlertType
import com.childhelper.core.common.model.CryDetectionEvent
import com.childhelper.core.common.model.DeviceStatusSnapshot
import com.childhelper.core.common.model.MonitorMode
import com.childhelper.core.common.model.MotionDetectionEvent
import com.childhelper.core.common.model.SosEvent
import com.childhelper.core.common.notification.NotificationSender
import com.childhelper.core.security.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central event pipeline that collects all detection events and emits metadata-only alerts.
 *
 * Privacy Guarantee:
 * - ALL alerts contain ONLY metadata (event type, timestamp, confidence, device status)
 * - NO raw audio data is ever included in alerts
 * - NO raw image/frame data is ever included in alerts
 * - NO media files are created or referenced
 * - All buffers are discarded immediately after analysis by upstream components
 *
 * This class is responsible for:
 * 1. Collecting cry detection events
 * 2. Collecting motion detection events
 * 3. Collecting SOS events
 * 4. Collecting camera obstruction events
 * 5. Enriching events with device status (battery, network, charging)
 * 6. Emitting Alert objects that guardians receive
 *
 * @param context Android application context
 * @param scope Coroutine scope for async processing
 */
class EventPipeline(
    private val context: Context,
    private val securePreferences: SecurePreferences,
    private val scope: CoroutineScope,
    private val notificationSender: NotificationSender
) {

    private val _alerts = MutableSharedFlow<Alert>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val alerts: Flow<Alert> = _alerts.asSharedFlow()

    companion object {
        private const val TAG = "EventPipeline"
        /** Max concurrent guardian notification sends (CRIT-5). */
        private const val MAX_CONCURRENT_SENDS = 4
        /** Debounce window in milliseconds — skip duplicate events of same type within this window. */
        private const val DEBOUNCE_MS = 5_000L
    }

    /**
     * CRIT-5 FIX: Semaphore limits concurrent notification sends to prevent OOM
     * under rapid detection (e.g., electrical noise causing false positives).
     * Without this, each event launches a new coroutine that performs network I/O
     * with retry backoff, keeping coroutines alive for seconds.
     */
    private val sendSemaphore = Semaphore(MAX_CONCURRENT_SENDS)

    /**
     * CRIT-5 FIX: Tracks the last send timestamp per event type for debouncing.
     * If the same event type arrives within [DEBOUNCE_MS], it is skipped.
     * Uses ConcurrentHashMap for thread-safe access from multiple coroutines.
     */
    private val lastSentTimestamps = ConcurrentHashMap<AlertType, Long>()

    /**
     * Submit a cry detection event. Creates and emits a metadata-only alert.
     *
     * @param event The cry detection event from CryDetector
     */
    /**
     * Submit a cry detection event. Creates and emits a metadata-only alert.
     *
     * @param event The cry detection event from CryDetector
     */
    fun submitCryEvent(event: CryDetectionEvent) {
        submitEvent(
            eventType = AlertType.CRY_DETECTED,
            timestamp = event.timestamp,
            confidence = event.confidence,
            childDeviceId = event.childDeviceId
        )
    }

    /**
     * Submit a motion detection event. Creates and emits a metadata-only alert.
     *
     * @param event The motion detection event from MotionDetector
     */
    fun submitMotionEvent(event: MotionDetectionEvent) {
        submitEvent(
            eventType = AlertType.MOTION_DETECTED,
            timestamp = event.timestamp,
            confidence = event.confidence,
            childDeviceId = event.childDeviceId
        )
    }

    /**
     * Submit an SOS event. Creates and emits a metadata-only alert.
     *
     * @param event The SOS event from SosManager
     */
    fun submitSosEvent(event: SosEvent) {
        submitEvent(
            eventType = AlertType.SOS_ACTIVATED,
            timestamp = event.timestamp,
            confidence = null,
            childDeviceId = event.childDeviceId,
            isHighPriority = true
        )
    }

    /**
     * Submit a camera obstruction event.
     * Emitted when the camera is covered or the view is too dark.
     */
    fun submitObstructionEvent() {
        submitEvent(
            eventType = AlertType.CAMERA_OBSTRUCTED,
            timestamp = System.currentTimeMillis(),
            confidence = null,
            childDeviceId = getDeviceIdSync()
        )
    }

    /**
     * Submit a device offline event.
     * Called when network connectivity is lost.
     */
    fun submitDeviceOfflineEvent() {
        scope.launch {
            val alert = Alert(
                id = UUID.randomUUID().toString(),
                eventType = AlertType.DEVICE_OFFLINE,
                timestamp = System.currentTimeMillis(),
                confidence = null,
                deviceStatus = getCurrentDeviceStatus(),
                childDeviceId = getDeviceIdSync()
            )
            emitAlert(alert)
        }
    }

    /**
     * Submit a low battery event.
     * Called when battery drops below a threshold.
     */
    fun submitLowBatteryEvent(batteryPercent: Int) {
        submitEvent(
            eventType = AlertType.LOW_BATTERY,
            timestamp = System.currentTimeMillis(),
            confidence = batteryPercent / 100f,
            childDeviceId = getDeviceIdSync()
        )
    }

    /**
     * Submit a call started event.
     */
    fun submitCallStartedEvent(sessionId: String) {
        scope.launch {
            val alert = Alert(
                id = UUID.randomUUID().toString(),
                eventType = AlertType.CALL_STARTED,
                timestamp = System.currentTimeMillis(),
                confidence = null,
                deviceStatus = getCurrentDeviceStatus(),
                childDeviceId = getDeviceIdSync()
            )
            emitAlert(alert)
        }
    }

    /**
     * Submit a call ended event.
     */
    fun submitCallEndedEvent(sessionId: String) {
        scope.launch {
            val alert = Alert(
                id = UUID.randomUUID().toString(),
                eventType = AlertType.CALL_ENDED,
                timestamp = System.currentTimeMillis(),
                confidence = null,
                deviceStatus = getCurrentDeviceStatus(),
                childDeviceId = getDeviceIdSync()
            )
            emitAlert(alert)
        }
    }

    /**
     * Submit a thermal warning event.
     * Emitted when the device temperature enters the WARM range (38-42 degrees C).
     * Triggers a guardian notification with the current temperature.
     *
     * @param temperatureCelsius The current device temperature in degrees Celsius.
     */
    fun submitThermalWarningEvent(temperatureCelsius: Float) {
        submitEvent(
            eventType = AlertType.THERMAL_WARNING,
            timestamp = System.currentTimeMillis(),
            confidence = temperatureCelsius / 100f,
            childDeviceId = getDeviceIdSync()
        )
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Thermal warning submitted: ${temperatureCelsius}C")
        }
    }

    /**
     * Submit a device overheating event.
     * Emitted when the device temperature exceeds the CRITICAL threshold (> 45 degrees C).
     * This is a high-priority alert that causes the monitoring service to stop for safety.
     *
     * @param temperatureCelsius The current device temperature in degrees Celsius.
     */
    fun submitDeviceOverheatingEvent(temperatureCelsius: Float) {
        submitEvent(
            eventType = AlertType.DEVICE_OVERHEATING,
            timestamp = System.currentTimeMillis(),
            confidence = temperatureCelsius / 100f,
            childDeviceId = getDeviceIdSync(),
            isHighPriority = true
        )
        Log.w(TAG, "Device overheating alert submitted: ${temperatureCelsius}C — monitoring stopped")
    }

    /**
     * CRIT-5 FIX: Centralized event submission with rate limiting and debouncing.
     * Uses a Semaphore to cap concurrent notification sends, and skips duplicate
     * event types that arrive within the debounce window.
     */
    private fun submitEvent(
        eventType: AlertType,
        timestamp: Long,
        confidence: Float? = null,
        childDeviceId: String,
        isHighPriority: Boolean = false
    ) {
        scope.launch {
            // Debounce: skip if same event type was sent within DEBOUNCE_MS
            val now = System.currentTimeMillis()
            val lastSent = lastSentTimestamps[eventType]
            if (lastSent != null && (now - lastSent) < DEBOUNCE_MS) {
                Log.d(TAG, "Debounced duplicate $eventType (last sent ${now - lastSent}ms ago)")
                return@launch
            }
            lastSentTimestamps[eventType] = now

            val alert = Alert(
                id = UUID.randomUUID().toString(),
                eventType = eventType,
                timestamp = timestamp,
                confidence = confidence,
                deviceStatus = getCurrentDeviceStatus(),
                childDeviceId = childDeviceId
            )

            emitAlert(alert)

            // Semaphore limits concurrent network sends to prevent OOM
            sendSemaphore.withPermit {
                sendGuardianNotification(alert, isHighPriority)
            }
        }
    }

    /**
     * Emit an alert to the internal flow.
     */
    private suspend fun emitAlert(alert: Alert) {
        try {
            _alerts.emit(alert)
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "Alert emitted: ${alert.eventType} (id=${alert.id})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit alert", e)
        }
    }

    /**
     * Send a notification to the guardian app via [NotificationSender].
     * The alert is serialized to JSON (metadata only) and delivered through
     * the backend's FCM infrastructure.
     *
     * Includes retry logic with exponential backoff for transient failures.
     *
     * @param alert The alert to send
     * @param isHighPriority Whether to send as high-priority (for SOS)
     */
    private suspend fun sendGuardianNotification(alert: Alert, isHighPriority: Boolean = false) {
        try {
            val result = notificationSender.sendAlert(alert, isHighPriority)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Guardian notification sent: ${alert.eventType} (highPriority=$isHighPriority)")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to send guardian notification after all retries", error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending guardian notification", e)
        }
    }

    /**
     * Get the current device status snapshot.
     * This is included in every alert so guardians know the device state.
     */
    private fun getCurrentDeviceStatus(): DeviceStatusSnapshot {
        val batteryStatus = getBatteryStatus()
        val networkType = getNetworkType()
        val monitorMode = getMonitorMode()

        return DeviceStatusSnapshot(
            batteryPercent = batteryStatus.first,
            isCharging = batteryStatus.second,
            networkType = networkType,
            monitorMode = monitorMode
        )
    }

    /**
     * Get current battery level and charging state.
     */
    private fun getBatteryStatus(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?: return Pair(100, false)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    /**
     * Get current network type (wifi, cellular, none).
     */
    private fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val networkCapabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return when {
            networkCapabilities == null -> "none"
            networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "none"
        }
    }

    /**
     * Get current monitor mode.
     */
    private fun getMonitorMode(): MonitorMode {
        // In production, this would check the current mode from preferences or state
        return MonitorMode.IDLE
    }

    private suspend fun getDeviceId(): String {
        return securePreferences.getString("device_id", "child_device") ?: "child_device"
    }

    /** Synchronous device ID fallback for callers that cannot suspend. */
    private fun getDeviceIdSync(): String {
        // Best-effort synchronous read — in practice the device ID is cached
        // in secure preferences and available immediately.
        return try {
            kotlinx.coroutines.runBlocking { getDeviceId() }
        } catch (e: Exception) {
            "child_device"
        }
    }
}
