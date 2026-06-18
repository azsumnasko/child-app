package com.childhelper.app.parent.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.app.parent.db.AlertEntity
import com.childhelper.app.parent.repository.AlertHistoryRepository
import com.childhelper.core.common.model.AlertType
import com.childhelper.core.common.model.RetentionPeriod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Filter type for alert history.
 */
enum class AlertFilterType {
    ALL, CRY, MOTION, SOS, DEVICE, CALL
}

/**
 * UI state for the alert history screen.
 */
data class AlertHistoryUiState(
    val allAlerts: List<AlertEntity> = emptyList(),
    val filteredAlerts: List<AlertEntity> = emptyList(),
    val currentFilter: AlertFilterType = AlertFilterType.ALL,
    val retentionPeriod: RetentionPeriod = RetentionPeriod.TWENTY_FOUR_HOURS,
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val showExportDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val exportInProgress: Boolean = false,
    val dataDeleted: Boolean = false,
    val errorMessage: String? = null
)

/**
 * ViewModel for the alert history screen.
 * Manages filtering by type, export, and delete operations.
 */
@HiltViewModel
class AlertHistoryViewModel @Inject constructor(
    private val alertRepository: AlertHistoryRepository
) : ViewModel() {

    private val _currentFilter = MutableStateFlow(AlertFilterType.ALL)
    private val _showExportDialog = MutableStateFlow(false)
    private val _showDeleteDialog = MutableStateFlow(false)
    private val _exportInProgress = MutableStateFlow(false)
    private val _dataDeleted = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AlertHistoryUiState> = combine(
        alertRepository.getAllAlerts(),
        _currentFilter,
        alertRepository.getRetentionPeriod(),
        alertRepository.getAlertCount(),
        _showExportDialog,
        _showDeleteDialog,
        _exportInProgress,
        _dataDeleted,
        _errorMessage
    ) { values ->
        val allAlerts = values[0] as List<AlertEntity>
        val filter = values[1] as AlertFilterType
        val retention = values[2] as RetentionPeriod
        val count = values[3] as Int

        val filtered = applyFilter(allAlerts, filter)

        AlertHistoryUiState(
            allAlerts = allAlerts,
            filteredAlerts = filtered,
            currentFilter = filter,
            retentionPeriod = retention,
            totalCount = count,
            isLoading = false,
            showExportDialog = values[4] as Boolean,
            showDeleteDialog = values[5] as Boolean,
            exportInProgress = values[6] as Boolean,
            dataDeleted = values[7] as Boolean,
            errorMessage = values[8] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AlertHistoryUiState(isLoading = true)
    )

    // --- Filtering ---

    fun setFilter(filter: AlertFilterType) {
        _currentFilter.value = filter
    }

    // --- Export ---

    fun showExportDialog() {
        _showExportDialog.value = true
    }

    fun dismissExportDialog() {
        _showExportDialog.value = false
    }

    /**
     * Export alert history as a privacy-safe summary (text format).
     * PRIVACY: Exports only metadata — no audio/video content.
     */
    fun exportHistory(): String {
        val alerts = uiState.value.filteredAlerts
        if (alerts.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("ChildHelper Alert History Export")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        sb.appendLine("Filter: ${uiState.value.currentFilter.name}")
        sb.appendLine("Total Alerts: ${alerts.size}")
        sb.appendLine("-".repeat(50))
        sb.appendLine()

        alerts.groupBy {
            java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(it.timestamp))
        }.forEach { (date, dayAlerts) ->
            sb.appendLine("Date: $date")
            dayAlerts.forEach { alert ->
                val time = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(alert.timestamp))
                val confidence = alert.confidence?.let { " (${(it * 100).toInt()}%)" } ?: ""
                sb.appendLine("  [$time] ${alert.eventType}$confidence")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    // --- Delete ---

    fun showDeleteDialog() {
        _showDeleteDialog.value = true
    }

    fun dismissDeleteDialog() {
        _showDeleteDialog.value = false
    }

    /**
     * Delete all alert history.
     */
    fun deleteAllHistory() {
        viewModelScope.launch {
            try {
                val deletedCount = alertRepository.deleteAllHistory()
                _dataDeleted.value = true
                _showDeleteDialog.value = false
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete history: ${e.localizedMessage}"
                _showDeleteDialog.value = false
            }
        }
    }

    /**
     * Delete a specific alert.
     */
    fun deleteAlert(alertId: String) {
        viewModelScope.launch {
            try {
                alertRepository.deleteAlert(alertId)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete alert: ${e.localizedMessage}"
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun resetDataDeletedFlag() {
        _dataDeleted.value = false
    }

    // --- Private ---

    private fun applyFilter(
        alerts: List<AlertEntity>,
        filter: AlertFilterType
    ): List<AlertEntity> {
        return when (filter) {
            AlertFilterType.ALL -> alerts
            AlertFilterType.CRY -> alerts.filter {
                it.eventType == AlertType.CRY_DETECTED.name
            }
            AlertFilterType.MOTION -> alerts.filter {
                it.eventType == AlertType.MOTION_DETECTED.name
            }
            AlertFilterType.SOS -> alerts.filter {
                it.eventType == AlertType.SOS_ACTIVATED.name
            }
            AlertFilterType.DEVICE -> alerts.filter {
                it.eventType == AlertType.DEVICE_OFFLINE.name ||
                    it.eventType == AlertType.LOW_BATTERY.name ||
                    it.eventType == AlertType.CAMERA_OBSTRUCTED.name
            }
            AlertFilterType.CALL -> alerts.filter {
                it.eventType == AlertType.CALL_STARTED.name ||
                    it.eventType == AlertType.CALL_ENDED.name
            }
        }
    }
}
