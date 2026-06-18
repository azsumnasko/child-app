package com.childhelper.app.child.ui.detection

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.app.child.detection.CryDetector
import com.childhelper.app.child.detection.EventPipeline
import com.childhelper.app.child.detection.MotionDetector
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.model.AlertType
import com.childhelper.core.common.model.CryDetectionEvent
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.MonitorMode
import com.childhelper.core.common.model.MotionDetectionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the detection overlay UI.
 *
 * Monitors cry and motion detection events and presents them
 * as non-intrusive visual indicators on the home screen.
 * All alerts are metadata-only — no raw media is stored or displayed.
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val cryDetector: CryDetector,
    private val motionDetector: MotionDetector,
    private val eventPipeline: EventPipeline
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetectionUiState())
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    private val _recentAlerts = MutableStateFlow<List<Alert>>(emptyList())
    val recentAlerts: StateFlow<List<Alert>> = _recentAlerts.asStateFlow()

    init {
        // Collect cry detection events
        viewModelScope.launch {
            cryDetector.cryEvents.collect { event ->
                onCryEvent(event)
            }
        }

        // Collect motion detection events
        viewModelScope.launch {
            motionDetector.motionEvents.collect { event ->
                onMotionEvent(event)
            }
        }

        // Collect alerts from event pipeline
        viewModelScope.launch {
            eventPipeline.alerts.collect { alert ->
                addAlert(alert)
            }
        }
    }

    fun startDetection(config: DetectionConfig, lifecycleOwner: LifecycleOwner) {
        viewModelScope.launch {
            cryDetector.startDetection(config)
            motionDetector.startDetection(config, lifecycleOwner)
            _uiState.update {
                it.copy(
                    isCryDetectionActive = config.cryEnabled,
                    isMotionDetectionActive = config.motionEnabled,
                    monitorMode = MonitorMode.BEDTIME
                )
            }
        }
    }

    fun stopDetection() {
        viewModelScope.launch {
            cryDetector.stopDetection()
            motionDetector.stopDetection()
            _uiState.update {
                it.copy(
                    isCryDetectionActive = false,
                    isMotionDetectionActive = false,
                    monitorMode = MonitorMode.IDLE
                )
            }
        }
    }

    private fun onCryEvent(event: CryDetectionEvent) {
        _uiState.update {
            it.copy(
                lastCryConfidence = event.confidence,
                cryEventCount = it.cryEventCount + 1,
                lastEventTimestamp = event.timestamp
            )
        }
    }

    private fun onMotionEvent(event: MotionDetectionEvent) {
        _uiState.update {
            it.copy(
                lastMotionConfidence = event.confidence,
                motionEventCount = it.motionEventCount + 1,
                lastEventTimestamp = event.timestamp
            )
        }
    }

    private fun addAlert(alert: Alert) {
        val currentAlerts = _recentAlerts.value.toMutableList()
        currentAlerts.add(0, alert)
        // Keep only the 10 most recent alerts
        if (currentAlerts.size > 10) {
            currentAlerts.removeAt(currentAlerts.size - 1)
        }
        _recentAlerts.value = currentAlerts

        // Update UI state with latest alert
        _uiState.update {
            it.copy(
                lastAlertType = alert.eventType,
                lastAlertTimestamp = alert.timestamp
            )
        }
    }

    fun clearAlert(alertId: String) {
        _recentAlerts.update { alerts ->
            alerts.filter { it.id != alertId }
        }
    }

    fun clearAllAlerts() {
        _recentAlerts.value = emptyList()
        _uiState.update {
            it.copy(
                lastAlertType = null,
                lastAlertTimestamp = 0
            )
        }
    }

    fun onCameraObstructed() {
        viewModelScope.launch {
            eventPipeline.submitObstructionEvent()
            _uiState.update {
                it.copy(
                    isCameraObstructed = true,
                    lastAlertType = AlertType.CAMERA_OBSTRUCTED,
                    lastAlertTimestamp = System.currentTimeMillis()
                )
            }
        }
    }

    fun onCameraClear() {
        _uiState.update { it.copy(isCameraObstructed = false) }
    }
}

/**
 * UI state for the detection overlay.
 * All fields are metadata — no raw media data.
 */
data class DetectionUiState(
    val isCryDetectionActive: Boolean = false,
    val isMotionDetectionActive: Boolean = false,
    val lastCryConfidence: Float = 0f,
    val lastMotionConfidence: Float = 0f,
    val cryEventCount: Int = 0,
    val motionEventCount: Int = 0,
    val isCameraObstructed: Boolean = false,
    val lastAlertType: AlertType? = null,
    val lastAlertTimestamp: Long = 0,
    val lastEventTimestamp: Long = 0,
    val monitorMode: MonitorMode = MonitorMode.IDLE
)
