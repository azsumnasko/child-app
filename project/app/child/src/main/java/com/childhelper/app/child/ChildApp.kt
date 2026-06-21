package com.childhelper.app.child

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.childhelper.core.network.signaling.WebRtcSignalingClient
import com.childhelper.core.security.LocaleManager
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class ChildApp : Application() {

    @Inject
    lateinit var securePreferences: SecurePreferences

    @Inject
    lateinit var signalingClient: WebRtcSignalingClient

    override fun onCreate() {
        super.onCreate()
        initLocale()
        createNotificationChannels()
        startSignalingPolling()
    }

    private fun initLocale() {
        if (!::securePreferences.isInitialized) {
            Log.w(TAG, "SecurePreferences not yet injected — skipping locale init")
            return
        }
        try {
            runBlocking {
                val lang = securePreferences.getString(LocaleManager.PREF_KEY_LANGUAGE)
                if (!lang.isNullOrBlank()) {
                    LocaleManager.cacheLanguage(lang)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Locale init failed", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channels = listOf(
                    NotificationChannel(CHANNEL_MONITORING, "Child Monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Continuous monitoring for cry and motion detection"
                        setShowBadge(false)
                    },
                    NotificationChannel(CHANNEL_ALERTS, "Safety Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Important safety alerts from the child device"
                        enableVibration(true)
                    },
                    NotificationChannel(CHANNEL_CALL, "Voice/Video Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Incoming and ongoing calls"
                        enableVibration(true)
                    },
                    NotificationChannel(CHANNEL_SOS, "SOS Emergency", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "SOS emergency alerts"
                        enableVibration(true)
                    }
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannels(channels)
            } catch (e: Exception) {
                Log.w(TAG, "Notification channel creation failed", e)
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

    companion object {
        const val CHANNEL_MONITORING = "child_monitoring"
        const val CHANNEL_ALERTS = "child_alerts"
        const val CHANNEL_CALL = "child_call"
        const val CHANNEL_SOS = "child_sos"
        private const val TAG = "ChildApp"
    }
}
