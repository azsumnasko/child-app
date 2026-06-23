package com.childhelper.core.p2p

import android.util.Log
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.notification.NotificationSender
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.api.SignalingApi
import com.childhelper.core.p2p.LocalPeerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid alert dispatcher that routes alerts through the best available channel:
 * - WiFi Direct (P2P) when devices are nearby — zero latency
 * - Server API when devices are on different networks — internet-wide
 *
 * Falls back automatically. Both channels implement [NotificationSender].
 */
@Singleton
class HybridNotificationSender @Inject constructor(
    private val p2pDispatcher: P2pAlertDispatcher,
    private val signalingApi: SignalingApi
) : NotificationSender {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private const val TAG = "HybridNotifSender"
        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS = longArrayOf(1000L, 2000L, 4000L)
    }

    override suspend fun sendAlert(alert: Alert, isHighPriority: Boolean): Result<Unit> {
        // Try P2P first (fastest, zero server dependency)
        if (p2pDispatcher.isPeerConnected()) {
            val p2pResult = p2pDispatcher.sendAlert(alert, isHighPriority)
            if (p2pResult.isSuccess) {
                Log.d(TAG, "Alert sent via P2P: ${alert.eventType}")
                return p2pResult
            }
        }

        // Fall back to server API with retry for transient failures
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

        var lastError: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                val response = signalingApi.sendNotification(alert.childDeviceId, payload)
                if (response.isSuccessful) {
                    Log.d(TAG, "Alert sent via server: ${alert.eventType}")
                    return Result.success(Unit)
                }
                // 4xx = don't retry; 5xx = retry
                if (response.code() in 400..499) {
                    Log.w(TAG, "Server rejected alert: ${response.code()}")
                    p2pDispatcher.sendAlert(alert, isHighPriority)
                    return Result.success(Unit)
                }
                Log.w(TAG, "Server error ${response.code()}, attempt ${attempt + 1}/${MAX_RETRIES + 1}")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Server unreachable, attempt ${attempt + 1}/${MAX_RETRIES + 1}", e)
            }
            if (attempt < MAX_RETRIES) {
                delay(RETRY_DELAYS[attempt])
            }
        }

        // All retries exhausted — queue locally for P2P when peer reconnects
        Log.w(TAG, "All server retries exhausted — queueing for P2P", lastError)
        p2pDispatcher.sendAlert(alert, isHighPriority)
        return Result.success(Unit)
    }
}
