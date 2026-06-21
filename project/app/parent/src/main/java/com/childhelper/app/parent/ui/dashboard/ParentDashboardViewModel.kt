package com.childhelper.app.parent.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.app.parent.db.AlertEntity
import com.childhelper.app.parent.repository.AlertHistoryRepository
import com.childhelper.core.common.model.AlertType
import com.childhelper.core.common.model.DeviceStatus
import com.childhelper.core.common.model.MonitorMode
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the parent dashboard.
 */
data class DashboardUiState(
    val deviceStatus: DeviceStatus = DeviceStatus(
        deviceId = "",
        isOnline = false,
        batteryPercent = 0,
        isCharging = false,
        networkType = "none",
        monitorMode = MonitorMode.IDLE,
        lastSeen = 0L
    ),
    val childName: String = "Child Device",
    val recentAlerts: List<AlertEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val unreadAlertCount: Int = 0
)

/**
 * ViewModel for the parent dashboard screen.
 * Manages device status, alert feed, and navigation events.
 */
@HiltViewModel
class ParentDashboardViewModel @Inject constructor(
    private val alertRepository: AlertHistoryRepository,
    private val securePreferences: SecurePreferences
) : ViewModel() {

    private val _deviceStatus = MutableStateFlow(
        DeviceStatus(
            deviceId = "",
            isOnline = false,
            batteryPercent = 0,
            isCharging = false,
            networkType = "",
            monitorMode = MonitorMode.IDLE,
            lastSeen = System.currentTimeMillis()
        )
    )

    private val _isRefreshing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _childName = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val childId = securePreferences.getString(KEY_PAIRED_CHILD_DEVICE_ID)
            val isPaired = securePreferences.getBoolean("is_paired", false)
            if (childId != null && isPaired) {
                _deviceStatus.value = _deviceStatus.value.copy(deviceId = childId)
                val name = securePreferences.getString(KEY_CHILD_NAME) ?: "Child Device"
                _childName.value = name
            } else {
                _childName.value = "No device paired"
            }
        }
    }

    /**
     * Combined UI state exposed as a single StateFlow.
     */
    val uiState: StateFlow<DashboardUiState> = combine(
        _deviceStatus,
        alertRepository.getRecentAlerts(50),
        _isRefreshing,
        _errorMessage,
        _childName
    ) { deviceStatus, alerts, refreshing, error, name ->
        DashboardUiState(
            deviceStatus = deviceStatus,
            childName = name,
            recentAlerts = alerts,
            isLoading = false,
            isRefreshing = refreshing,
            errorMessage = error,
            unreadAlertCount = alerts.count { it.timestamp > deviceStatus.lastSeen - 300_000 }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(isLoading = true)
    )

    /**
     * Navigation events — one-time events to trigger navigation.
     */
    private val _navigationEvent = MutableStateFlow<DashboardNavigationEvent?>(null)
    val navigationEvent = _navigationEvent.asStateFlow()

    /**
     * Refresh device status and alerts.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Enforce retention policy on refresh
                alertRepository.scheduleRetentionEnforcement()

                // Update last seen
                _deviceStatus.update { it.copy(lastSeen = System.currentTimeMillis()) }
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to refresh: ${e.localizedMessage}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Navigate to live view screen.
     */
    fun onLiveViewClick() {
        _navigationEvent.value = DashboardNavigationEvent.NavigateToLiveView
    }

    /**
     * Navigate to alert history screen.
     */
    fun onAlertHistoryClick() {
        _navigationEvent.value = DashboardNavigationEvent.NavigateToAlertHistory
    }

    /**
     * Navigate to settings screen.
     */
    fun onSettingsClick() {
        _navigationEvent.value = DashboardNavigationEvent.NavigateToSettings
    }

    fun onPairNewDeviceClick() {
        _navigationEvent.value = DashboardNavigationEvent.NavigateToPairing
    }

    /**
     * Consume navigation event after handling.
     */
    fun consumeNavigationEvent() {
        _navigationEvent.value = null
    }

    /**
     * Update device status (called from FCM push or polling).
     * Also persists key fields to SecurePreferences for offline access.
     */
    fun updateDeviceStatus(status: DeviceStatus) {
        _deviceStatus.value = status
        viewModelScope.launch {
            securePreferences.putString(KEY_LAST_DEVICE_ID, status.deviceId)
            securePreferences.putBoolean(KEY_LAST_ONLINE, status.isOnline)
        }
    }

    /**
     * Simulate a mock alert for testing.
     */
    fun simulateMockAlert() {
        viewModelScope.launch {
            val mockAlert = AlertEntity(
                id = java.util.UUID.randomUUID().toString(),
                eventType = AlertType.CRY_DETECTED.name,
                timestamp = System.currentTimeMillis(),
                confidence = 0.85f,
                childDeviceId = _deviceStatus.value.deviceId,
                batteryPercent = _deviceStatus.value.batteryPercent,
                isCharging = _deviceStatus.value.isCharging,
                networkType = _deviceStatus.value.networkType,
                monitorMode = _deviceStatus.value.monitorMode.name
            )
            alertRepository.insertEntity(mockAlert)
        }
    }
}

/**
 * One-time navigation events from the dashboard.
 */
sealed class DashboardNavigationEvent {
    data object NavigateToLiveView : DashboardNavigationEvent()
    data object NavigateToAlertHistory : DashboardNavigationEvent()
    data object NavigateToSettings : DashboardNavigationEvent()
    data object NavigateToPairing : DashboardNavigationEvent()
}

private const val KEY_CHILD_NAME = "parent_child_name"
private const val KEY_LAST_DEVICE_ID = "parent_last_device_id"
private const val KEY_LAST_ONLINE = "parent_last_online"
private const val KEY_PAIRED_CHILD_DEVICE_ID = "paired_child_device_id"
