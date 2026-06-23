package com.childhelper.app.child.service

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.childhelper.app.child.detection.CameraPipeline
import com.childhelper.app.child.detection.CryDetector
import com.childhelper.app.child.detection.EventPipeline
import com.childhelper.app.child.detection.MotionDetector
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.MonitorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Single source of truth** for the global monitoring state.
 *
 * Before this class existed, monitoring state was scattered across at least 7
 * different components (CryDetector, MotionDetector, CameraPipeline,
 * AudioPipeline, MonitoringService, ChildHomeViewModel, BedtimeViewModel).
 * This caused race conditions where two components could disagree on whether
 * monitoring was active, leading to leaked detectors or double-starts.
 *
 * ## Responsibilities
 *
 * - Owns the single [isMonitoring] flag (a cold [StateFlow]).
 * - Provides atomic **start** and **stop** operations that synchronise all
 *   detectors (cry, motion, camera).
 * - Publishes [MonitoringState] so observers can react to state transitions.
 * - Coordinates thermal throttling by monitoring [ThermalMonitor.thermalState]
 *   and applying the appropriate mitigations (reduce resolution, disable video,
 *   or shut down entirely).
 *
 * ## Usage
 *
 * Only [MonitoringService] should call [startMonitoring] and [stopMonitoring].
 * ViewModels and UI layers observe [isMonitoring] and [monitoringState] but do
 * **not** mutate them directly — they request changes via service intents.
 *
 * @param cryDetector Audio-based cry detection
 * @param motionDetector Camera-based motion detection
 * @param cameraPipeline Camera frame source for motion detection
 * @param eventPipeline Alert emission pipeline
 * @param scope Coroutine scope for thermal monitoring and async operations
 */
@Singleton
class MonitoringCoordinator(
    private val cryDetector: CryDetector,
    private val motionDetector: MotionDetector,
    private val cameraPipeline: CameraPipeline,
    private val eventPipeline: EventPipeline,
    private val scope: CoroutineScope
) {

    /**
     * Current monitoring state. Use this in UI layers to reflect monitoring status.
     */
    private val _monitoringState = MutableStateFlow<MonitoringState>(MonitoringState.Idle)
    val monitoringState: StateFlow<MonitoringState> = _monitoringState.asStateFlow()

    /**
     * Convenience boolean derived from [monitoringState].
     *
     * `true` when monitoring is **actively running** (normal or throttled).
     * `false` when [MonitoringState.Idle] or fully stopped.
     *
     * Uses [stateIn] with [SharingStarted.Eagerly] to avoid coroutine leaks in
     * property initializers and ensure the flow is always ready to collect.
     */
    val isMonitoring: StateFlow<Boolean> = _monitoringState
        .map { state -> state is MonitoringState.Active || state is MonitoringState.ThermalThrottled }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The [DetectionConfig] used for the current (or last) monitoring session.
     * Exposed so the binder can report it to UI layers.
     */
    private var _currentConfig: DetectionConfig = DetectionConfig()
    val currentConfig: DetectionConfig get() = _currentConfig

    /**
     * Whether monitoring was stopped because of a critical thermal state.
     * CRIT-7 FIX: Using AtomicBoolean ensures atomic visibility across threads.
     * The binder reads this from the main thread while the thermal collector
     * writes it from a background coroutine.
     */
    private val _isThermalShutdown = AtomicBoolean(false)
    val isThermalShutdown: Boolean get() = _isThermalShutdown.get()

    private var thermalJob: Job? = null
    private var cryEventJob: Job? = null
    private var motionEventJob: Job? = null

    companion object {
        private const val TAG = "MonitoringCoordinator"
    }

    /**
     * Start monitoring with the given configuration.
     *
     * This is an **atomic** operation: either all requested detectors start
     * successfully or none do. The [monitoringState] transitions to
     * [MonitoringState.Active] only after detectors have been started.
     *
     * @param config Detection configuration (sensitivity, enabled flags, thresholds)
     * @param lifecycleOwner The lifecycle owner to bind the camera pipeline to
     * @param thermalMonitor Optional thermal monitor to start throttling observation
     */
    fun startMonitoring(
        config: DetectionConfig,
        lifecycleOwner: LifecycleOwner,
        thermalMonitor: ThermalMonitor? = null
    ) {
        if (monitoringState.value !is MonitoringState.Idle) {
            Log.w(TAG, "Monitoring already active (${monitoringState.value}); ignoring start request")
            return
        }

        Log.i(TAG, "Starting monitoring with config: sensitivity=${config.sensitivity}")
        _currentConfig = config
        _isThermalShutdown.set(false)

        // Start cry detection
        if (config.cryEnabled) {
            try {
                cryDetector.startDetection(config)
                Log.i(TAG, "Cry detection started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start cry detection", e)
            }
        }

        // Start motion detection
        if (config.motionEnabled) {
            try {
                motionDetector.startDetection(config, lifecycleOwner)
                Log.i(TAG, "Motion detection started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start motion detection", e)
            }
        }

        // Cancel stale event-forwarding jobs before creating new ones
        cryEventJob?.cancel()
        cryEventJob = null
        motionEventJob?.cancel()
        motionEventJob = null

        // Forward detection events to the alert pipeline
        cryEventJob = scope.launch {
            cryDetector.cryEvents.collect { eventPipeline.submitCryEvent(it) }
        }
        motionEventJob = scope.launch {
            motionDetector.motionEvents.collect { eventPipeline.submitMotionEvent(it) }
        }

        // Publish the state change AFTER detectors are started
        _monitoringState.value = MonitoringState.Active(config)
        eventPipeline.setMonitorMode(MonitorMode.IDLE)
        Log.i(TAG, "Monitoring state → Active")

        // Start thermal monitoring if provided
        thermalMonitor?.let { startThermalMonitoring(it) }
    }

    /**
     * Stop all monitoring and release detector resources.
     *
     * This is also atomic: all detectors are stopped before the state transitions
     * to [MonitoringState.Idle]. Observers are guaranteed that when
     * [isMonitoring] emits `false`, no detectors are running.
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping monitoring")

        // Cancel thermal observation first so it doesn't restart detectors
        thermalJob?.cancel()
        thermalJob = null

        // Cancel event-forwarding jobs
        cryEventJob?.cancel()
        cryEventJob = null
        motionEventJob?.cancel()
        motionEventJob = null

        // Stop all detectors unconditionally
        cryDetector.stopDetection()
        motionDetector.stopDetection()
        cameraPipeline.stopAnalysis()

        // Publish the state change AFTER detectors are stopped
        _monitoringState.value = MonitoringState.Idle
        eventPipeline.setMonitorMode(MonitorMode.IDLE)
        Log.i(TAG, "Monitoring state → Idle")
    }

    /**
     * Suspend camera-based monitoring (motion detection) to free the camera
     * for a WebRTC call. Audio-based cry detection continues running.
     * Call [resumeCameraAfterCall] when the call ends.
     * Must be called from a coroutine (uses Dispatchers.Main for CameraX).
     */
    suspend fun suspendCameraForCall() {
        Log.i(TAG, "Suspending motion detection for call (camera stays bound for Preview)")
        motionEventJob?.cancel()
        motionEventJob = null
        motionDetector.stopDetection()
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            cameraPipeline.suspendImageAnalysisOnly()
        }
    }

    /**
     * Resume camera-based monitoring after a WebRTC call ends.
     * Only restarts if monitoring was active before the call.
     */
    fun resumeCameraAfterCall() {
        val config = _currentConfig
        if (config == null || monitoringState.value !is MonitoringState.Active) {
            Log.d(TAG, "Skipping camera resume — monitoring not active")
            return
        }
        Log.i(TAG, "Resuming camera after call")
        try {
            val lifecycleOwner = cameraPipeline.getSavedLifecycleOwner()
                ?: return
            if (config.motionEnabled) {
                cameraPipeline.resumeImageAnalysis()
                motionDetector.startDetection(config, lifecycleOwner)
                motionEventJob = scope.launch {
                    motionDetector.motionEvents.collect { eventPipeline.submitMotionEvent(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume camera after call", e)
        }
    }

    /**
     * Update detection configuration while monitoring is active.
     *
     * Restarts detectors atomically with the new configuration.
     *
     * @param config New detection configuration
     * @param lifecycleOwner The lifecycle owner to bind the camera pipeline to
     */
    fun updateConfig(config: DetectionConfig, lifecycleOwner: LifecycleOwner) {
        _currentConfig = config

        // Restart detectors with new config
        cryDetector.stopDetection()
        motionDetector.stopDetection()

        if (config.cryEnabled) {
            cryDetector.startDetection(config)
        }
        if (config.motionEnabled) {
            motionDetector.startDetection(config, lifecycleOwner)
        }

        Log.i(TAG, "Monitoring config updated: sensitivity=${config.sensitivity}")
    }

    /**
     * Start observing [ThermalMonitor.thermalState] and apply mitigations.
     *
     * - [ThermalState.NORMAL]: Resume full operation
     * - [ThermalState.WARM]: Reduce camera resolution to 480p
     * - [ThermalState.HOT]: Disable video, keep audio-only detection
     * - [ThermalState.CRITICAL]: Stop monitoring entirely for safety
     */
    private fun startThermalMonitoring(thermalMonitor: ThermalMonitor) {
        thermalMonitor.startMonitoring()

        thermalJob = scope.launch {
            thermalMonitor.thermalState.collectLatest { state ->
                if (!isActive) return@collectLatest

                when (state) {
                    ThermalState.NORMAL -> {
                        if (cameraPipeline.currentPowerMode.value != com.childhelper.app.child.detection.PowerMode.CRITICAL) {
                            cameraPipeline.resumeNormalMode()
                        }
                        cryDetector.setAudioOnlyMode(false)
                        // Only update state if we were previously throttled
                        if (_monitoringState.value is MonitoringState.ThermalThrottled) {
                            _monitoringState.value = MonitoringState.Active(_currentConfig)
                        }
                    }
                    ThermalState.WARM -> {
                        val temp = thermalMonitor.getLastTemperature()
                        Log.w(TAG, "Device WARM: ${temp}C — reducing camera resolution to 480p")
                        eventPipeline.submitThermalWarningEvent(temp)
                        _monitoringState.value = MonitoringState.ThermalThrottled(state)
                    }
                    ThermalState.HOT -> {
                        val temp = thermalMonitor.getLastTemperature()
                        Log.w(TAG, "Device HOT: ${temp}C — disabling video, audio-only detection")
                        cameraPipeline.stopAnalysis()
                        cryDetector.setAudioOnlyMode(true)
                        eventPipeline.submitThermalWarningEvent(temp)
                        _monitoringState.value = MonitoringState.ThermalThrottled(state)
                    }
                    ThermalState.CRITICAL -> {
                        val temp = thermalMonitor.getLastTemperature()
                        Log.e(TAG, "Device CRITICAL: ${temp}C — stopping monitoring for safety")
                        // CRIT-7 FIX: Set the atomic flag BEFORE any cleanup so that
                        // bound activities querying via the binder see the thermal
                        // shutdown reason even if cleanup races ahead.
                        _isThermalShutdown.set(true)
                        eventPipeline.submitDeviceOverheatingEvent(temp)
                        stopMonitoring()
                    }
                }
            }
        }
    }

    /**
     * Sealed class representing the high-level monitoring state machine.
     */
    sealed class MonitoringState {
        /** Monitoring is not running. All detectors are stopped. */
        data object Idle : MonitoringState()

        /** Monitoring is active with the given configuration. */
        data class Active(val config: DetectionConfig) : MonitoringState()

        /** Monitoring is running but thermally throttled. */
        data class ThermalThrottled(val thermalState: ThermalState) : MonitoringState()
    }
}
