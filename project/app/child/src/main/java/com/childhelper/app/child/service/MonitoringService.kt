package com.childhelper.app.child.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Lifecycle
import com.childhelper.app.child.ChildApp
import com.childhelper.app.child.R
import com.childhelper.app.child.ui.home.ChildHomeActivity
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.MonitorMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Foreground service for continuous child monitoring.
 *
 * This service runs persistently in the background to provide:
 * - Cry detection via AudioPipeline + CryDetector
 * - Motion detection via CameraPipeline + MotionDetector
 * - Camera obstruction detection
 * - Thermal monitoring with automatic throttling
 * - Low-power mode with resolution/fps reduction
 * - Metadata-only alerts sent to guardians
 * - Low battery notifications
 *
 * Detector coordination is delegated to [MonitoringCoordinator], which serves as
 * the **single source of truth** for monitoring state. This eliminates race
 * conditions where multiple components could disagree on whether monitoring
 * is active.
 *
 * Thermal Safety:
 * - Monitors device temperature every 30 seconds via [ThermalMonitor]
 * - WARM (38-42 degrees C): Reduces camera resolution to 480p
 * - HOT (42-45 degrees C): Disables video, keeps audio-only detection
 * - CRITICAL (> 45 degrees C): Stops monitoring service entirely, alerts parent
 *
 * Low-Power Mode:
 * - Battery < 20% and not charging: Reduces camera to 480x360 @ 10fps
 * - Battery < 10%: Disables video entirely, audio-only detection continues
 *
 * Privacy:
 * - Uses FOREGROUND_SERVICE_TYPE_CAMERA and FOREGROUND_SERVICE_TYPE_MICROPHONE
 * - NO MediaRecorder — only AudioRecord for raw buffer access
 * - NO video or audio files are ever created
 * - All buffers are discarded immediately after analysis
 * - Only metadata alerts (event type, timestamp, confidence, device status) are sent
 *
 * The service uses a partial wake lock to ensure monitoring continues
 * even when the device screen is off.
 */
@AndroidEntryPoint
class MonitoringService : Service(), LifecycleOwner {

    @Inject
    lateinit var monitoringCoordinator: MonitoringCoordinator

    @Inject
    lateinit var eventPipeline: com.childhelper.app.child.detection.EventPipeline

    @Inject
    lateinit var thermalMonitor: ThermalMonitor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var detectionConfig: DetectionConfig = DetectionConfig()

    private val binder = MonitoringBinder()

    // Lifecycle support for CameraX binding
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private const val TAG = "MonitoringService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "ChildHelper::MonitoringWakeLock"

        // Actions
        const val ACTION_START_MONITORING = "com.childhelper.app.child.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.childhelper.app.child.STOP_MONITORING"
        const val ACTION_UPDATE_CONFIG = "com.childhelper.app.child.UPDATE_CONFIG"
        const val EXTRA_CONFIG = "detection_config"

        // JSON serializer for DetectionConfig
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Serialize a DetectionConfig to a JSON string for passing via Intent extras.
         */
        fun serializeConfig(config: DetectionConfig): String = json.encodeToString(DetectionConfig.serializer(), config)

        /**
         * Deserialize a DetectionConfig from a JSON string.
         */
        fun deserializeConfig(jsonString: String?): DetectionConfig? =
            try {
                jsonString?.let { json.decodeFromString(DetectionConfig.serializer(), it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deserialize DetectionConfig", e)
                null
            }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MonitoringService created")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // CRIT-2 FIX: startForeground() MUST be called immediately in onCreate(),
        // before any work is done. If the service is restarted by the OS via
        // START_STICKY and onStartCommand() receives a null intent, we must already
        // be in foreground — otherwise Android 12+ kills us immediately.
        val notification = createMonitoringNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        // Acquire wake lock to keep monitoring active
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRIT-2 FIX: intent can be null when the OS restarts the service via START_STICKY.
        // When null, we must restart monitoring with a default config — otherwise the
        // service runs without any active detection, and (before this fix) startForeground()
        // was never called, causing an immediate crash on Android 12+.
        if (intent == null || intent.action == null) {
            Log.w(TAG, "onStartCommand with null intent — OS restarted service, resuming with defaults")
            startMonitoring(DetectionConfig())
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START_MONITORING -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                val config = deserializeConfig(configJson) ?: DetectionConfig()
                startMonitoring(config)
            }
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_UPDATE_CONFIG -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                deserializeConfig(configJson)?.let { updateConfig(it) }
            }
        }

        // If service is killed, restart it
        return START_STICKY
    }

    /**
     * Start monitoring with the given configuration.
     * Puts the service in the foreground with a persistent notification.
     *
     * All detector coordination is delegated to [MonitoringCoordinator],
     * which atomically starts cry detection, motion detection, and thermal
     * observation while publishing a single [MonitoringCoordinator.isMonitoring]
     * state.
     */
    private fun startMonitoring(config: DetectionConfig) {
        detectionConfig = config

        // Persist that monitoring is active so BootReceiver can restart after reboot
        BootReceiver.setMonitoringActive(this, true)

        // Acquire wake lock
        try {
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes, will be re-acquired
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock", e)
        }

        // Delegate ALL detector and thermal coordination to the single source of truth
        monitoringCoordinator.startMonitoring(config, this, thermalMonitor)

        // Monitor battery levels independently (service-level concern)
        serviceScope.launch {
            monitorBattery()
        }

        // Re-acquire wake lock periodically
        serviceScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000) // Every 5 minutes
                try {
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire(10 * 60 * 1000L)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Wake lock re-acquire failed", e)
                }
            }
        }

        Log.i(TAG, "Monitoring started with config: sensitivity=${config.sensitivity}")
    }

    /**
     * Stop all monitoring and release resources.
     *
     * Delegates detector shutdown to [MonitoringCoordinator], then releases
     * service-specific resources (wake lock, foreground notification).
     */
    private fun stopMonitoring() {
        Log.i(TAG, "Stopping monitoring")

        // Clear the boot-restart flag so we don't auto-start after reboot
        BootReceiver.setMonitoringActive(this, false)

        // Single, atomic stop through the coordinator
        monitoringCoordinator.stopMonitoring()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock", e)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Update detection configuration while monitoring is active.
     *
     * Delegates to [MonitoringCoordinator] which restarts detectors atomically.
     */
    private fun updateConfig(config: DetectionConfig) {
        detectionConfig = config
        monitoringCoordinator.updateConfig(config, this)
        Log.i(TAG, "Monitoring config updated: sensitivity=${config.sensitivity}")
    }

    /**
     * Monitor battery level and send alerts when low.
     *
     * This remains a service-level concern because it runs independently of
     * whether cry or motion detection is enabled.
     */
    private suspend fun monitorBattery() {
        var lastBatteryPercent = 100

        while (true) {
            delay(5 * 60 * 1000) // Check every 5 minutes

            try {
                val batteryStatus = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                batteryStatus?.let { intent ->
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

                    if (batteryPct <= 20 && lastBatteryPercent > 20) {
                        // Battery dropped below 20%
                        eventPipeline.submitLowBatteryEvent(batteryPct)
                    } else if (batteryPct <= 10 && lastBatteryPercent > 10) {
                        // Battery dropped below 10%
                        eventPipeline.submitLowBatteryEvent(batteryPct)
                    }

                    lastBatteryPercent = batteryPct
                }
            } catch (e: Exception) {
                Log.w(TAG, "Battery monitoring error", e)
            }
        }
    }

    /**
     * Create the persistent foreground notification for monitoring.
     */
    private fun createMonitoringNotification(): Notification {
        val intent = Intent(this, ChildHomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MonitoringService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, ChildApp.CHANNEL_MONITORING)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(getString(R.string.monitoring_notification_text))
            .setSmallIcon(R.drawable.ic_monitoring)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.monitoring_notification_stop_action),
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        Log.i(TAG, "MonitoringService destroyed")

        // Ensure everything is stopped — delegate to coordinator for detectors
        try {
            monitoringCoordinator.stopMonitoring()
            thermalMonitor.stopMonitoring()

            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during service destroy", e)
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Binder for activities to communicate with the service.
     *
     * All monitoring-status queries are served from [MonitoringCoordinator],
     * ensuring that activities and the service always agree on state.
     */
    inner class MonitoringBinder : Binder() {
        fun getService(): MonitoringService = this@MonitoringService

        /**
         * Return whether monitoring is currently active.
         *
         * Reads from [MonitoringCoordinator.isMonitoring] so that the UI
         * always reflects the single source of truth.
         */
        fun isMonitoring(): Boolean {
            return monitoringCoordinator.isMonitoring.value
        }

        fun getCurrentMode(): MonitorMode {
            return if (isMonitoring()) MonitorMode.BEDTIME else MonitorMode.IDLE
        }

        /**
         * Returns `true` if monitoring was stopped due to critical thermal state.
         * The parent should be notified when this is true.
         */
        fun isThermalShutdown(): Boolean = monitoringCoordinator.isThermalShutdown

        /**
         * Get the last-read device temperature from the thermal monitor.
         *
         * @return Temperature in degrees Celsius, or 0f if no reading available.
         */
        fun getLastTemperature(): Float = thermalMonitor.getLastTemperature()
    }
}
