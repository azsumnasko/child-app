package com.childhelper.core.p2p

import android.util.Log
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.notification.NotificationSender
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*

class P2pAlertDispatcher(
    private val p2pManager: LocalP2pManager,
    private val scope: CoroutineScope
) : NotificationSender {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingAlerts = mutableListOf<Alert>()

    fun isPeerConnected(): Boolean = p2pManager.peerFlow.value is LocalPeerState.Connected

    init {
        scope.launch {
            p2pManager.peerFlow.collect { state ->
                if (state is LocalPeerState.Connected && pendingAlerts.isNotEmpty()) {
                    val alerts = pendingAlerts.toList()
                    pendingAlerts.clear()
                    alerts.forEach { sendAlertInternal(it) }
                }
            }
        }
    }

    override suspend fun sendAlert(alert: Alert, isHighPriority: Boolean): Result<Unit> {
        if (p2pManager.peerFlow.value is LocalPeerState.Connected) {
            sendAlertInternal(alert)
        } else {
            synchronized(pendingAlerts) {
                if (pendingAlerts.size < 100) pendingAlerts.add(alert)
            }
        }
        return Result.success(Unit)
    }

    private fun sendAlertInternal(alert: Alert) {
        val type = when (alert.eventType.name) {
            "CRY_DETECTED" -> P2pMessageType.ALERT_CRY
            "MOTION_DETECTED" -> P2pMessageType.ALERT_MOTION
            "SOS_ACTIVATED" -> P2pMessageType.ALERT_SOS
            "CAMERA_OBSTRUCTED" -> P2pMessageType.ALERT_CAMERA
            "LOW_BATTERY" -> P2pMessageType.ALERT_BATTERY
            else -> P2pMessageType.STATUS_UPDATE
        }
        val payload = json.encodeToString(AlertPayload(
            alertId = alert.id, eventType = alert.eventType.name,
            timestamp = alert.timestamp, confidence = alert.confidence,
            childDeviceId = alert.childDeviceId,
            batteryPercent = alert.deviceStatus.batteryPercent,
            isCharging = alert.deviceStatus.isCharging,
            networkType = alert.deviceStatus.networkType,
            monitorMode = alert.deviceStatus.monitorMode.name
        ))
        scope.launch { p2pManager.sendMessage(P2pMessage(type = type, payload = payload)) }
    }

    companion object { private const val TAG = "P2pAlertDispatcher" }
}
