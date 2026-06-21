package com.childhelper.app.parent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.childhelper.app.parent.repository.AlertHistoryRepository
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.model.AlertType
import com.childhelper.core.common.model.DeviceStatusSnapshot
import com.childhelper.core.common.model.MonitorMode
import com.childhelper.core.network.api.SignalingApi
import com.childhelper.core.network.push.FcmService
import com.childhelper.core.network.signaling.WebRtcSignalingClient
import com.childhelper.core.security.LocaleManager
import com.childhelper.core.security.SecurePreferences
import com.childhelper.app.parent.di.AppScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject

@HiltAndroidApp
class ParentApp : Application() {

    @Inject
    lateinit var securePreferences: SecurePreferences

    @Inject
    lateinit var alertHistoryRepository: AlertHistoryRepository

    @Inject
    lateinit var signalingClient: WebRtcSignalingClient

    @Inject
    lateinit var signalingApi: SignalingApi

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            val crashLog = File(filesDir, "crash_parent.txt")
            try {
                StringWriter().use { sw ->
                    PrintWriter(sw).use { pw -> ex.printStackTrace(pw) }
                    crashLog.writeText(sw.toString())
                }
            } catch (_: Exception) {}
            Log.e(TAG, "FATAL CRASH", ex)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        try {
            super.onCreate()
            Log.i(TAG, "onCreate start")
            initLocale()
            Log.i(TAG, "initLocale done")
            createNotificationChannels()
            startAlertIngestion()
            startSignalingPolling()
            startNotificationPolling()
            Log.i(TAG, "onCreate done")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crashed", e)
            val crashLog = File(filesDir, "crash_parent.txt")
            try {
                StringWriter().use { sw ->
                    PrintWriter(sw).use { pw -> e.printStackTrace(pw) }
                    crashLog.writeText(sw.toString())
                }
            } catch (_: Exception) {}
            throw e
        }
    }

    private fun initLocale() {
        try {
            if (!::securePreferences.isInitialized) {
                Log.w(TAG, "SP not injected")
                return
            }
            kotlinx.coroutines.runBlocking {
                val lang = securePreferences.getString(LocaleManager.PREF_KEY_LANGUAGE)
                if (!lang.isNullOrBlank()) LocaleManager.cacheLanguage(lang)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Locale init failed", e)
        }
    }

    private fun startAlertIngestion() {
        appScope.launch {
            FcmService.alertFlow.collect { alert ->
                try {
                    alertHistoryRepository.insertAlert(alert)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist alert", e)
                }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannels(listOf(
                    NotificationChannel(CHANNEL_ALERTS, "Safety Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Important safety alerts from the child device"; enableVibration(true)
                    },
                    NotificationChannel(CHANNEL_GENERAL, "General Notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "App notifications and status updates"
                    }
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Channels failed", e)
            }
        }
    }

    private fun startSignalingPolling() {
        try {
            signalingClient.startPolling()
        } catch (e: Exception) {
            Log.w(TAG, "Signaling polling start failed", e)
        }
    }

    private fun startNotificationPolling() {
        appScope.launch {
            while (true) {
                try {
                    val deviceId = securePreferences.getString("device_id")
                    if (deviceId.isNullOrBlank()) {
                        delay(5000)
                        continue
                    }
                    val alerts = signalingApi.getPendingAlerts(deviceId)
                    for (alertPayload in alerts) {
                        val eventTypeStr = alertPayload["eventType"]?.jsonPrimitive?.content ?: "UNKNOWN"
                        val eventType = try { AlertType.valueOf(eventTypeStr) } catch (_: Exception) { AlertType.SOS_ACTIVATED }
                        val timestamp = alertPayload["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
                        val childDeviceId = alertPayload["childDeviceId"]?.jsonPrimitive?.content ?: "unknown"
                        val priority = alertPayload["priority"]?.jsonPrimitive?.content ?: "normal"
                        Log.i(TAG, "Received alert via poll: eventType=$eventTypeStr, child=$childDeviceId, priority=$priority")
                        try {
                            val deviceStatus = DeviceStatusSnapshot(
                                batteryPercent = alertPayload["batteryPercent"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                                isCharging = alertPayload["isCharging"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                                networkType = alertPayload["networkType"]?.jsonPrimitive?.content ?: "unknown",
                                monitorMode = alertPayload["monitorMode"]?.jsonPrimitive?.content?.let {
                                    try { MonitorMode.valueOf(it) } catch (_: Exception) { MonitorMode.IDLE }
                                } ?: MonitorMode.IDLE
                            )
                            alertHistoryRepository.insertAlert(
                                Alert(
                                    id = alertPayload["alertId"]?.jsonPrimitive?.content ?: "poll_$timestamp",
                                    eventType = eventType,
                                    timestamp = timestamp,
                                    confidence = alertPayload["confidence"]?.jsonPrimitive?.content?.toFloatOrNull(),
                                    deviceStatus = deviceStatus,
                                    childDeviceId = childDeviceId
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist polled alert", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notification polling failed", e)
                }
                delay(5000)
            }
        }
    }

    companion object {
        const val CHANNEL_ALERTS = "parent_alerts_channel"
        const val CHANNEL_GENERAL = "parent_general_channel"
        const val TAG = "ParentApp"
    }
}
