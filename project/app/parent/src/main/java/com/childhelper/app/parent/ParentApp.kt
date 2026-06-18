package com.childhelper.app.parent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.childhelper.core.security.LocaleManager
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Application class for the parent-facing dashboard app.
 * Initializes Hilt dependency injection and app-wide configuration.
 */
@HiltAndroidApp
class ParentApp : Application() {

    @Inject
    lateinit var securePreferences: SecurePreferences

    override fun onCreate() {
        super.onCreate()
        initLocale()
        createNotificationChannels()
    }

    private fun initLocale() {
        try {
            runBlocking {
                val lang = securePreferences.getString(LocaleManager.PREF_KEY_LANGUAGE)
                if (!lang.isNullOrBlank()) {
                    LocaleManager.cacheLanguage(lang)
                }
            }
        } catch (_: Exception) {
            // Fall back to system default
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Safety Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Important safety alerts from the child device"
                    enableVibration(true)
                },
                NotificationChannel(
                    CHANNEL_GENERAL,
                    "General Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "App notifications and status updates"
                }
            )

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannels(channels)
        }
    }

    companion object {
        const val CHANNEL_ALERTS = "parent_alerts_channel"
        const val CHANNEL_GENERAL = "parent_general_channel"
    }
}
