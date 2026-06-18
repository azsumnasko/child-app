package com.childhelper.app.child.service

import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Thermal state enumeration representing device temperature zones.
 *
 * States map to specific temperature thresholds and corresponding actions:
 * - [NORMAL]: Device is within safe operating temperature. Full monitoring capability.
 * - [WARM]: Device is warming up. Log warning and reduce camera resolution to 480p.
 * - [HOT]: Device is hot. Disable video, keep audio-only detection active.
 * - [CRITICAL]: Device is critically overheating. Stop monitoring service entirely and alert parent.
 */
enum class ThermalState {
    /** Normal operating temperature (< 38 degrees C). No action required. */
    NORMAL,

    /** Elevated temperature (38 degrees C to 42 degrees C). Warning logged, camera resolution reduced. */
    WARM,

    /** Hot temperature (42 degrees C to 45 degrees C). Video disabled, audio-only detection continues. */
    HOT,

    /** Critical temperature (> 45 degrees C). Monitoring service stopped, parent alerted. */
    CRITICAL
}

/**
 * Listener interface for thermal state transitions.
 *
 * Implement this interface to receive callbacks when the device thermal state changes.
 * Each callback corresponds to a specific [ThermalState] and should perform the
 * appropriate mitigation action.
 */
interface ThermalStateListener {
    /** Called when device enters normal thermal operating range. */
    fun onNormal() {}

    /** Called when device becomes warm. Camera resolution should be reduced to 480p. */
    fun onWarm(temperatureCelsius: Float) {}

    /** Called when device becomes hot. Video should be disabled, audio-only detection continues. */
    fun onHot(temperatureCelsius: Float) {}

    /** Called when device is critically overheating. Monitoring should stop entirely. */
    fun onCritical(temperatureCelsius: Float) {}
}

/**
 * Monitors device thermal state and emits state changes via a Kotlin [Flow].
 *
 * Checks device temperature every 30 seconds during active monitoring sessions.
 * Supports multiple temperature reading strategies with graceful fallback:
 *
 * 1. **HardwarePropertiesManager** (API 24+): Reads device temperatures from hardware sensors.
 * 2. **Sysfs thermal zones**: Reads `/sys/class/thermal/thermal_zoneX/temp` for CPU/battery temp.
 * 3. **PowerManager thermal status**: Maps Android thermal status to approximate temperatures.
 * 4. **CPU usage estimation**: Rough estimate based on process CPU load (last resort fallback).
 *
 * Temperature thresholds (Celsius):
 * - Normal: < 38 degrees C
 * - Warm: 38 degrees C to 42 degrees C
 * - Hot: 42 degrees C to 45 degrees C
 * - Critical: > 45 degrees C
 *
 * The [thermalState] flow emits distinct values only when the state changes, preventing
 * duplicate emissions for the same thermal zone.
 *
 * @param context Application context for accessing system services.
 * @param scope Coroutine scope for the monitoring polling loop.
 */
class ThermalMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val hardwarePropertiesManager: HardwarePropertiesManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
        } else null

    private val listeners = mutableListOf<ThermalStateListener>()
    private var monitoringJob: kotlinx.coroutines.Job? = null
    private var lastTemperature: Float = 0f

    companion object {
        private const val TAG = "ThermalMonitor"

        /** Polling interval in milliseconds (30 seconds). */
        private const val POLL_INTERVAL_MS = 30_000L

        /** Thresholds in degrees Celsius for state transitions. */
        private const val THRESHOLD_WARM = 38f
        private const val THRESHOLD_HOT = 42f
        private const val THRESHOLD_CRITICAL = 45f

        /** Sysfs paths for thermal zone temperature readings (in millidegrees). */
        private const val THERMAL_ZONE_BASE = "/sys/class/thermal"
        private val PREFERRED_ZONE_TYPES = listOf("battery", "cpu", "soc", "pmic")
    }

    /**
     * A [Flow] that emits the current [ThermalState] whenever it changes.
     *
     * The flow uses [distinctUntilChanged] to suppress consecutive duplicate emissions.
     * Collect this flow in a service or ViewModel to react to thermal changes:
     *
     * ```
     * serviceScope.launch {
     *     thermalMonitor.thermalState.collect { state ->
     *         when (state) {
     *             ThermalState.WARM -> reduceCameraResolution()
     *             ThermalState.HOT -> disableVideo()
     *             ThermalState.CRITICAL -> stopMonitoring()
     *             else -> { /* normal — no action */ }
     *         }
     *     }
     * }
     * ```
     */
    val thermalState: Flow<ThermalState> = callbackFlow {
        var currentState = ThermalState.NORMAL

        // Polling loop that checks temperature every 30 seconds
        val job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    val temperature = readTemperature()
                    lastTemperature = temperature
                    val newState = temperatureToState(temperature)

                    if (newState != currentState) {
                        currentState = newState
                        Log.i(TAG, "Thermal state changed to $newState (temperature=${temperature}C)")
                        notifyListeners(newState, temperature)
                        trySend(newState)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading temperature, assuming NORMAL", e)
                    val fallbackState = ThermalState.NORMAL
                    if (fallbackState != currentState) {
                        currentState = fallbackState
                        trySend(fallbackState)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        // Emit initial state
        try {
            val initialTemp = readTemperature()
            lastTemperature = initialTemp
            currentState = temperatureToState(initialTemp)
            trySend(currentState)
        } catch (e: Exception) {
            trySend(ThermalState.NORMAL)
        }

        awaitClose {
            job.cancel()
        }
    }.distinctUntilChanged()

    /**
     * Start the thermal monitoring polling loop.
     *
     * This launches a coroutine that reads the device temperature every 30 seconds
     * and emits state changes through the [thermalState] flow and registered listeners.
     */
    fun startMonitoring() {
        if (monitoringJob != null) return

        monitoringJob = scope.launch(Dispatchers.Default) {
            thermalState.collect { state ->
                Log.d(TAG, "Thermal state: $state (temp=${lastTemperature}C)")
            }
        }
        Log.i(TAG, "Thermal monitoring started")
    }

    /**
     * Stop the thermal monitoring polling loop and release resources.
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.i(TAG, "Thermal monitoring stopped")
    }

    /**
     * Register a [ThermalStateListener] to receive callback notifications on state changes.
     *
     * @param listener The listener to register.
     */
    fun addListener(listener: ThermalStateListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    /**
     * Unregister a previously registered [ThermalStateListener].
     *
     * @param listener The listener to remove.
     */
    fun removeListener(listener: ThermalStateListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Get the last-read temperature in degrees Celsius.
     *
     * @return The most recent temperature reading, or 0f if no reading has been taken.
     */
    fun getLastTemperature(): Float = lastTemperature

    /**
     * Read the current device temperature in degrees Celsius.
     *
     * Tries multiple strategies in order of accuracy:
     * 1. HardwarePropertiesManager (API 24+)
     * 2. Sysfs thermal zone files
     * 3. PowerManager thermal status mapping
     * 4. CPU usage estimation (fallback)
     */
    private suspend fun readTemperature(): Float = withContext(Dispatchers.IO) {
        // Strategy 1: HardwarePropertiesManager (most accurate, API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                hardwarePropertiesManager?.let { hpm ->
                    val temps: FloatArray = hpm.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, HardwarePropertiesManager.TEMPERATURE_CURRENT)
                    if (temps.isNotEmpty()) {
                        val avgTemp = temps.filter { it > 0 }.average().toFloat()
                        if (avgTemp > 0) {
                            return@withContext avgTemp
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission for HardwarePropertiesManager, trying sysfs")
            } catch (e: Exception) {
                Log.w(TAG, "HardwarePropertiesManager unavailable, trying sysfs")
            }
        }

        // Strategy 2: Sysfs thermal zones
        try {
            val sysfsTemp = readSysfsTemperature()
            if (sysfsTemp > 0) {
                return@withContext sysfsTemp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sysfs thermal reading failed, trying PowerManager")
        }

        // Strategy 3: PowerManager thermal status (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val thermalStatus = powerManager?.currentThermalStatus
                    ?: PowerManager.THERMAL_STATUS_NONE
                val mappedTemp = thermalStatusToTemperature(thermalStatus)
                if (mappedTemp > 0) {
                    return@withContext mappedTemp
                }
            } catch (e: Exception) {
                Log.w(TAG, "PowerManager thermal status failed, using CPU estimation fallback")
            }
        }

        // Strategy 4: CPU usage estimation (last resort)
        return@withContext estimateTemperatureFromCpuUsage()
    }

    /**
     * Read temperature from sysfs thermal zone files.
     *
     * Scans `/sys/class/thermal/thermal_zoneX/type` to find battery/CPU zones,
     * then reads the corresponding `temp` file. Values are in millidegrees Celsius.
     *
     * @return Temperature in degrees Celsius, or 0f if no readable zone found.
     */
    private fun readSysfsTemperature(): Float {
        val thermalDir = File(THERMAL_ZONE_BASE)
        if (!thermalDir.exists() || !thermalDir.isDirectory) return 0f

        val zoneDirs = thermalDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("thermal_zone")
        } ?: return 0f

        // First pass: look for preferred zone types (battery, cpu, soc)
        for (zoneDir in zoneDirs.sortedBy { it.name }) {
            try {
                val typeFile = File(zoneDir, "type")
                if (typeFile.exists()) {
                    val type = typeFile.readText().trim().lowercase()
                    if (PREFERRED_ZONE_TYPES.any { type.contains(it) }) {
                        val tempFile = File(zoneDir, "temp")
                        if (tempFile.exists()) {
                            val tempMillidegrees = tempFile.readText().trim().toLongOrNull() ?: continue
                            if (tempMillidegrees > 0) {
                                return tempMillidegrees / 1000f
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        // Second pass: read any available thermal zone
        for (zoneDir in zoneDirs.sortedBy { it.name }) {
            try {
                val tempFile = File(zoneDir, "temp")
                if (tempFile.exists()) {
                    val tempMillidegrees = tempFile.readText().trim().toLongOrNull() ?: continue
                    if (tempMillidegrees > 0 && tempMillidegrees < 100000) {
                        // Sanity check: between 0C and 100C
                        return tempMillidegrees / 1000f
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        return 0f
    }

    /**
     * Map Android [PowerManager] thermal status constants to approximate temperatures.
     *
     * These are rough estimates based on typical device thermal behavior:
     * - NONE (0): ~35 degrees C
     * - LIGHT (1): ~38 degrees C
     * - MODERATE (2): ~40 degrees C
     * - SEVERE (3): ~43 degrees C
     * - CRITICAL (4): ~47 degrees C
     * - EMERGENCY (5): ~50 degrees C
     * - SHUTDOWN (6): ~55 degrees C
     *
     * @param status The [PowerManager] thermal status constant.
     * @return Estimated temperature in degrees Celsius.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun thermalStatusToTemperature(status: Int): Float {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> 35f
            PowerManager.THERMAL_STATUS_LIGHT -> 38f
            PowerManager.THERMAL_STATUS_MODERATE -> 40f
            PowerManager.THERMAL_STATUS_SEVERE -> 43f
            PowerManager.THERMAL_STATUS_CRITICAL -> 47f
            PowerManager.THERMAL_STATUS_EMERGENCY -> 50f
            PowerManager.THERMAL_STATUS_SHUTDOWN -> 55f
            else -> 35f
        }
    }

    /**
     * Estimate device temperature from process CPU usage.
     *
     * This is a coarse fallback used when no thermal sensors are accessible.
     * Reads the process's CPU jiffies from `/proc/self/stat` and estimates
     * temperature contribution. The result is clamped to realistic ranges.
     *
     * @return Estimated temperature in degrees Celsius (clamped to 30-50C).
     */
    private fun estimateTemperatureFromCpuUsage(): Float {
        return try {
            val pid = Process.myPid()
            val statFile = File("/proc/$pid/stat")
            if (statFile.exists()) {
                val stats = statFile.readText().trim().split(" ")
                // utime is field 14, stime is field 15 (0-indexed: 13, 14)
                val utime = stats.getOrNull(13)?.toLongOrNull() ?: 0L
                val stime = stats.getOrNull(14)?.toLongOrNull() ?: 0L
                val totalJiffies = utime + stime

                // Very rough heuristic: higher CPU usage correlates with warmth
                // Base 32C + up to 15C based on total CPU time
                val estimated = 32f + (totalJiffies % 1000) / 1000f * 15f
                estimated.coerceIn(30f, 50f)
            } else {
                35f // Safe default assumption
            }
        } catch (e: Exception) {
            35f // Safe default when all methods fail
        }
    }

    /**
     * Convert a temperature reading to a [ThermalState] based on configured thresholds.
     *
     * @param celsius Temperature in degrees Celsius.
     * @return The corresponding [ThermalState].
     */
    private fun temperatureToState(celsius: Float): ThermalState {
        return when {
            celsius >= THRESHOLD_CRITICAL -> ThermalState.CRITICAL
            celsius >= THRESHOLD_HOT -> ThermalState.HOT
            celsius >= THRESHOLD_WARM -> ThermalState.WARM
            else -> ThermalState.NORMAL
        }
    }

    /**
     * Notify all registered listeners of a thermal state change.
     *
     * @param state The new thermal state.
     * @param temperatureCelsius The temperature that triggered the change.
     */
    private fun notifyListeners(state: ThermalState, temperatureCelsius: Float) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    when (state) {
                        ThermalState.NORMAL -> listener.onNormal()
                        ThermalState.WARM -> listener.onWarm(temperatureCelsius)
                        ThermalState.HOT -> listener.onHot(temperatureCelsius)
                        ThermalState.CRITICAL -> listener.onCritical(temperatureCelsius)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Listener notification failed", e)
                }
            }
        }
    }
}
