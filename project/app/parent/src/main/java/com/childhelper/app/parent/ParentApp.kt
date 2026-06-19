package com.childhelper.app.parent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.childhelper.core.security.LocaleManager
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject

@HiltAndroidApp
class ParentApp : Application() {

    @Inject
    lateinit var securePreferences: SecurePreferences

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

    companion object {
        const val CHANNEL_ALERTS = "parent_alerts_channel"
        const val CHANNEL_GENERAL = "parent_general_channel"
        const val TAG = "ParentApp"
    }
}
