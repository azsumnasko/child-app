package com.childhelper.core.common.events

import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.model.CallSession
import com.childhelper.core.common.model.DeviceStatus
import com.childhelper.core.common.model.PairingSession

/**
 * Sealed class representing application-wide events broadcast through a shared
 * event bus (typically a Kotlin [ kotlinx.coroutines.flow.SharedFlow ]).
 *
 * These events decouple modules so that, for example, the detection pipeline
 * can emit alerts without directly referencing the network or UI layers.
 *
 * **Threading:** All events are emitted on the main dispatcher. Consumers should
 * offload heavy work to background dispatchers.
 */
sealed class AppEvent {

    /**
     * Emitted when a new alert is generated on the child device or received
     * by the parent device.
     *
     * @property alert The alert containing metadata about the detected event.
     */
    data class AlertReceived(val alert: Alert) : AppEvent()

    /**
     * Emitted when the child device's online status or telemetry changes.
     *
     * @property deviceId The child device whose status changed.
     * @property status The updated device status snapshot.
     */
    data class DeviceStatusChanged(val deviceId: String, val status: DeviceStatus) : AppEvent()

    /**
     * Emitted when a pairing session transitions between [PairingSession.status] states.
     *
     * @property session The updated pairing session.
     */
    data class PairingStateChanged(val session: PairingSession) : AppEvent()

    /**
     * Emitted when a voice/video call session changes state.
     *
     * @property session The updated call session.
     */
    data class CallStateChanged(val session: CallSession) : AppEvent()

    /**
     * Emitted when a new FCM push token is generated or refreshed.
     *
     * @property token The new FCM registration token.
     */
    data class PushTokenRefreshed(val token: String) : AppEvent()

    /**
     * Emitted when the child device requests that the parent open the live view.
     *
     * This is typically triggered by the child pressing a "call parent" button.
     *
     * @property childDeviceId The device requesting the live view.
     */
    data class LiveViewRequested(val childDeviceId: String) : AppEvent()

    /**
     * Emitted when the app detects that network connectivity was lost or restored.
     *
     * @property isAvailable Whether the device currently has network access.
     */
    data class NetworkAvailabilityChanged(val isAvailable: Boolean) : AppEvent()

    /**
     * Emitted when the child device's battery falls below a critical threshold.
     *
     * @property deviceId The device reporting low battery.
     * @property batteryPercent Current battery level (0–100).
     */
    data class LowBatteryWarning(val deviceId: String, val batteryPercent: Int) : AppEvent()
}

/**
 * Type alias for the event bus shared flow type used across the application.
 *
 * Consumers should collect this flow in a coroutine scope tied to their lifecycle.
 * The flow is hot (shared) — multiple collectors receive every event.
 */
typealias AppEventBus = kotlinx.coroutines.flow.SharedFlow<AppEvent>
