package com.childhelper.app.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Receiver for BOOT_COMPLETED that auto-restarts [MonitoringService] if it was
 * active before the device rebooted.
 *
 * This ensures the child device resumes monitoring after a power cycle without
 * requiring the parent to manually open the app.
 *
 * OEM Considerations:
 * - Xiaomi/MIUI blocks BOOT_COMPLETED by default; user must enable auto-start
 *   in app settings. The [OemBatteryManager] guides the user through this.
 * - OPPO/ColorOS and Samsung may also restrict boot receivers; whitelisting
 *   via [OemBatteryManager] improves reliability.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "boot_monitoring_prefs"
        private const val KEY_MONITORING_ACTIVE = "monitoring_active"

        private fun getEncryptedPrefs(context: Context): SharedPreferences {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        /**
         * Persist whether monitoring is currently active so we can restore
         * the state after a reboot.
         *
         * This uses EncryptedSharedPreferences to protect the flag at rest.
         */
        fun setMonitoringActive(context: Context, active: Boolean) {
            try {
                getEncryptedPrefs(context)
                    .edit()
                    .putBoolean(KEY_MONITORING_ACTIVE, active)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist monitoring state", e)
            }
        }

        /**
         * Check whether monitoring was active before reboot.
         */
        fun wasMonitoringActive(context: Context): Boolean {
            return try {
                getEncryptedPrefs(context)
                    .getBoolean(KEY_MONITORING_ACTIVE, false)
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        Log.i(TAG, "Boot completed received — checking if monitoring should restart")

        if (!wasMonitoringActive(context)) {
            Log.i(TAG, "Monitoring was not active before reboot — nothing to restart")
            return
        }

        // Restart MonitoringService
        val serviceIntent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "MonitoringService restarted after boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart MonitoringService after boot", e)
        }
    }
}
