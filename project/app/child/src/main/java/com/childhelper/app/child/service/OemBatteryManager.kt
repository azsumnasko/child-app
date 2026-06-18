package com.childhelper.app.child.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * Manages OEM-specific battery optimization whitelisting.
 *
 * Chinese OEMs (Xiaomi, OPPO, vivo, Huawei, OnePlus) and Samsung aggressively kill
 * background apps and foreground services unless explicitly whitelisted. This class:
 *
 * - Detects the device OEM brand via [Build.MANUFACTURER]
 * - Checks whether the app is already exempt from battery optimizations
 * - Provides OEM-specific whitelist intents to guide the user to the correct settings page
 * - Shows a dialog via [showWhitelistDialog] explaining why whitelisting is needed
 *
 * **Critical:** Without whitelisting, the monitoring service will be killed within:
 * - 1-2 hours on Xiaomi/MIUI
 * - 30 minutes on OPPO/ColorOS
 * - Several hours on Samsung/OneUI (varies by OneUI version)
 *
 * @param context Application context
 */
class OemBatteryManager(private val context: Context) {

    companion object {
        private const val TAG = "OemBatteryManager"

        /** Preference key tracking whether we've shown the whitelist dialog */
        private const val PREFS_NAME = "oem_battery_prefs"
        private const val KEY_DIALOG_SHOWN = "whitelist_dialog_shown"
    }

    /**
     * Known OEM brands that require special handling.
     */
    enum class OemBrand {
        XIAOMI, OPPO, VIVO, SAMSUNG, HUAWEI, ONEPLUS, OTHER
    }

    /**
     * Data class representing a single step in the whitelist guide.
     */
    data class WhitelistStep(
        val title: String,
        val description: String
    )

    /**
     * Result of checking whitelist status.
     */
    data class WhitelistStatus(
        val isWhitelisted: Boolean,
        val oemBrand: OemBrand,
        val canRequestSystemDialog: Boolean,
        val steps: List<WhitelistStep>
    )

    /**
     * Detect the OEM brand from [Build.MANUFACTURER].
     */
    fun detectOem(): OemBrand {
        return when {
            Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("Redmi", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("POCO", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("MIUI", ignoreCase = true) -> OemBrand.XIAOMI

            Build.MANUFACTURER.contains("OPPO", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("Realme", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("ColorOS", ignoreCase = true) -> OemBrand.OPPO

            Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("iQOO", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("Funtouch", ignoreCase = true) -> OemBrand.VIVO

            Build.MANUFACTURER.contains("samsung", ignoreCase = true) -> OemBrand.SAMSUNG

            Build.MANUFACTURER.contains("HUAWEI", ignoreCase = true) ||
                    Build.MANUFACTURER.contains("HONOR", ignoreCase = true) -> OemBrand.HUAWEI

            Build.MANUFACTURER.contains("OnePlus", ignoreCase = true) -> OemBrand.ONEPLUS

            else -> OemBrand.OTHER
        }
    }

    /**
     * Check whether the app is already exempt from battery optimizations.
     *
     * @return `true` if the app is whitelisted (can run in background without restriction)
     */
    @SuppressLint("BatteryLife")
    fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not check battery optimization status", e)
            false
        }
    }

    /**
     * Get the comprehensive whitelist status for this device.
     *
     * Checks both the standard Android battery optimization exemption AND
     * provides OEM-specific manual steps as fallback.
     */
    fun getWhitelistStatus(): WhitelistStatus {
        val oem = detectOem()
        val isWhitelisted = isIgnoringBatteryOptimizations()
        val steps = getOemSteps(oem)

        // On API 23+ we can show the standard system dialog;
        // OEM-specific steps are always provided as fallback.
        val canRequestSystemDialog = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

        return WhitelistStatus(isWhitelisted, oem, canRequestSystemDialog, steps)
    }

    /**
     * Launch the standard Android battery optimization request dialog.
     *
     * This is the preferred path on stock/near-stock Android and works on
     * Samsung/OnePlus. On Xiaomi/OPPO/vivo/Huawei it is usually insufficient
     * on its own — the user must also follow the OEM-specific steps.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization request dialog", e)
        }
    }

    /**
     * Launch the OEM-specific battery/settings page if an intent is available.
     *
     * All intents are wrapped in try-catch because not all OEMs expose stable
     * settings URLs and they may vary across OS versions.
     *
     * @return `true` if an intent was launched, `false` otherwise
     */
    fun openOemSettings(): Boolean {
        val oem = detectOem()
        val intents = getOemIntents(oem)

        for (intent in intents) {
            try {
                // Verify the intent can be handled
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    Log.i(TAG, "Launched OEM settings: ${intent.action} ${intent.data}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "OEM intent failed: ${intent.action}", e)
                // Try next intent
            }
        }

        Log.w(TAG, "No OEM intent could be launched for $oem")
        return false
    }

    /**
     * Check whether the whitelist dialog has already been shown to the user.
     */
    fun hasShownDialog(): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DIALOG_SHOWN, false)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Mark that the whitelist dialog has been shown.
     */
    fun markDialogShown() {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DIALOG_SHOWN, true)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark dialog shown", e)
        }
    }

    /**
     * Get the OEM-specific whitelist steps for the given brand.
     */
    private fun getOemSteps(oem: OemBrand): List<WhitelistStep> {
        return when (oem) {
            OemBrand.XIAOMI -> listOf(
                WhitelistStep(
                    "Step 1: Auto-start permission",
                    "Go to Settings → Apps → Permissions → Auto-start, and enable auto-start for this app."
                ),
                WhitelistStep(
                    "Step 2: Battery saver",
                    "Go to Settings → Battery → App Battery Saver, select this app, and choose 'No restrictions'."
                ),
                WhitelistStep(
                    "Step 3: Lock in recents",
                    "Open the recent apps screen, find this app, and tap the lock icon to prevent it from being cleared."
                )
            )

            OemBrand.OPPO -> listOf(
                WhitelistStep(
                    "Step 1: Allow background running",
                    "Go to Settings → Battery → App Battery Management, select this app, and enable 'Allow background activity'."
                ),
                WhitelistStep(
                    "Step 2: Disable battery optimization",
                    "Go to Settings → Battery → Battery Optimization, select this app, and choose 'Don't optimize'."
                ),
                WhitelistStep(
                    "Step 3: Startup manager",
                    "Go to Settings → Apps → Startup Manager, and allow this app to run on startup."
                )
            )

            OemBrand.VIVO -> listOf(
                WhitelistStep(
                    "Step 1: High background power usage",
                    "Go to Settings → Battery → High background power usage, and allow this app."
                ),
                WhitelistStep(
                    "Step 2: Background power consumption",
                    "Go to Settings → Battery → Background power consumption management, and allow this app."
                ),
                WhitelistStep(
                    "Step 3: Auto-start",
                    "Go to Settings → Apps → Permissions → Auto-start, and enable this app."
                )
            )

            OemBrand.SAMSUNG -> listOf(
                WhitelistStep(
                    "Step 1: Battery optimization",
                    "Go to Settings → Battery → Background usage limits, and disable battery optimization for this app."
                ),
                WhitelistStep(
                    "Step 2: Sleeping apps",
                    "Go to Settings → Battery → Background usage limits → Never sleeping apps, and add this app."
                ),
                WhitelistStep(
                    "Step 3: Adaptive battery",
                    "Go to Settings → Battery → Adaptive battery, and add this app as an exception if needed."
                )
            )

            OemBrand.HUAWEI -> listOf(
                WhitelistStep(
                    "Step 1: Protected apps",
                    "Go to Settings → Battery → App launch, select this app, and enable 'Manage manually' with all three options checked."
                ),
                WhitelistStep(
                    "Step 2: Battery optimization",
                    "Go to Settings → Apps → Settings → Special access → Battery optimization, and set this app to 'Don't optimize'."
                )
            )

            OemBrand.ONEPLUS -> listOf(
                WhitelistStep(
                    "Step 1: Battery optimization",
                    "Go to Settings → Battery → Battery optimization, and set this app to 'Don't optimize'."
                ),
                WhitelistStep(
                    "Step 2: Adaptive battery",
                    "Go to Settings → Battery → Adaptive battery, and disable it for this app."
                ),
                WhitelistStep(
                    "Step 3: Recent apps lock",
                    "Open the recent apps screen, find this app, and tap the lock icon."
                )
            )

            OemBrand.OTHER -> listOf(
                WhitelistStep(
                    "Disable battery optimization",
                    "Go to Settings → Apps → Special app access → Battery optimization, find this app, and select 'Don't optimize'."
                )
            )
        }
    }

    /**
     * Get OEM-specific intents for opening the relevant settings pages.
     *
     * Multiple intents are returned as fallbacks since OEM settings URLs vary
     * across OS versions and are not always stable.
     */
    private fun getOemIntents(oem: OemBrand): List<Intent> {
        val packageName = context.packageName

        return when (oem) {
            OemBrand.XIAOMI -> listOf(
                // MIUI autostart settings
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                // MIUI app permissions
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_package_uid", context.applicationInfo.uid)
                },
                // Generic battery optimization
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            OemBrand.OPPO -> listOf(
                // ColorOS battery settings
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
                    )
                ),
                // Alternative ColorOS path
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                    )
                ),
                // Generic battery optimization
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            OemBrand.VIVO -> listOf(
                // FuntouchOS battery settings
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.vivo.abe",
                        "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                    )
                ),
                // Alternative vivo path
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.iqoo.daemon",
                        "com.iqoo.daemon.ui.ActivityUtils"
                    )
                ),
                // Generic battery optimization
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            OemBrand.SAMSUNG -> listOf(
                // Samsung battery settings
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                ),
                // Alternative Samsung path
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"
                    )
                ),
                // Sleeping apps
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.SleepingAppsActivity"
                    )
                ),
                // Generic battery optimization
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            OemBrand.HUAWEI -> listOf(
                // Huawei phone manager / app launch
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                ),
                // Alternative Huawei path
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.AppStartupActivity"
                    )
                ),
                // Battery optimization
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                ),
                // Generic battery optimization
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            OemBrand.ONEPLUS -> listOf(
                // OnePlus battery optimization
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                ),
                // Alternative OnePlus path
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$BatteryOptimizationActivity"
                    )
                ),
                // Generic battery optimization
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            OemBrand.OTHER -> listOf(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            )
        }
    }
}
