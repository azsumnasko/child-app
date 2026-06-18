package com.childhelper.app.parent.repository

import com.childhelper.app.parent.db.AlertDao
import com.childhelper.app.parent.db.AlertEntity
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.model.AlertType
import com.childhelper.core.common.model.RetentionPeriod
import com.childhelper.core.security.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for alert history operations.
 * Enforces retention policies and provides privacy-safe metadata-only access.
 */
@Singleton
class AlertHistoryRepository(
    private val alertDao: AlertDao,
    private val preferences: SecurePreferences
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val RETENTION_KEY = "alert_history_retention"
        private const val DEFAULT_RETENTION = "TWENTY_FOUR_HOURS"
    }

    private val _retentionPeriod = MutableStateFlow(RetentionPeriod.TWENTY_FOUR_HOURS)

    init {
        repositoryScope.launch {
            val stored = preferences.getString(RETENTION_KEY) ?: DEFAULT_RETENTION
            _retentionPeriod.value = try {
                RetentionPeriod.valueOf(stored)
            } catch (_: IllegalArgumentException) {
                RetentionPeriod.TWENTY_FOUR_HOURS
            }
        }
    }

    // --- Read Operations ---

    /**
     * Stream of all alerts, newest first.
     */
    fun getAllAlerts(): Flow<List<AlertEntity>> =
        alertDao.getAllAlerts().flowOn(Dispatchers.IO)

    /**
     * Stream of alerts filtered by type.
     */
    fun getAlertsByType(type: AlertType): Flow<List<AlertEntity>> =
        alertDao.getAlertsByType(type.name).flowOn(Dispatchers.IO)

    /**
     * Stream of recent alerts with a limit.
     */
    fun getRecentAlerts(limit: Int): Flow<List<AlertEntity>> =
        alertDao.getRecentAlerts(limit).flowOn(Dispatchers.IO)

    /**
     * Stream of alert count.
     */
    fun getAlertCount(): Flow<Int> =
        alertDao.getAlertCount().flowOn(Dispatchers.IO)

    /**
     * Get alerts within a specific time range.
     */
    fun getAlertsByDateRange(startTime: Long, endTime: Long): Flow<List<AlertEntity>> =
        alertDao.getAlertsByDateRange(startTime, endTime).flowOn(Dispatchers.IO)

    // --- Write Operations ---

    /**
     * Insert a single alert from a domain model.
     */
    suspend fun insertAlert(alert: Alert) {
        val entity = AlertEntity.fromAlertModel(alert)
        alertDao.insert(entity)
    }

    /**
     * Insert a pre-built entity directly.
     */
    suspend fun insertEntity(entity: AlertEntity) {
        alertDao.insert(entity)
    }

    /**
     * Insert multiple alerts in batch.
     */
    suspend fun insertAll(alerts: List<AlertEntity>) {
        alertDao.insertAll(alerts)
    }

    // --- Retention Policy ---

    /**
     * Get the current retention period setting.
     */
    fun getRetentionPeriod(): Flow<RetentionPeriod> =
        _retentionPeriod.asStateFlow()

    /**
     * Set the retention period. Triggers cleanup if period is shortened.
     */
    suspend fun setRetentionPeriod(period: RetentionPeriod) {
        val oldPeriod = _retentionPeriod.value
        preferences.putString(RETENTION_KEY, period.name)
        _retentionPeriod.value = period
        // If retention got shorter, clean up immediately
        if (isShorterRetention(oldPeriod, period)) {
            enforceRetention(period)
        }
    }

    /**
     * Enforce the retention policy by deleting expired alerts.
     */
    suspend fun enforceRetention(period: RetentionPeriod? = null) {
        val effectivePeriod = period ?: getRetentionPeriod().first()
        if (effectivePeriod == RetentionPeriod.OFF) {
            // Retention is off — keep everything, but we still respect
            // a max cap to prevent unbounded growth
            val maxAge = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            alertDao.deleteOlderThan(maxAge)
            return
        }

        val cutoff = calculateCutoffTimestamp(effectivePeriod)
        alertDao.deleteOlderThan(cutoff)
    }

    /**
     * Schedule periodic retention enforcement.
     */
    fun scheduleRetentionEnforcement() {
        repositoryScope.launch {
            enforceRetention()
        }
    }

    // --- Data Deletion ---

    /**
     * Securely delete all alert history. Irreversible.
     */
    suspend fun deleteAllHistory(): Int {
        return alertDao.deleteAll()
    }

    /**
     * Delete a specific alert by ID.
     */
    suspend fun deleteAlert(alertId: String): Int {
        return alertDao.deleteById(alertId)
    }

    // --- Private Helpers ---

    private fun calculateCutoffTimestamp(period: RetentionPeriod): Long {
        val now = System.currentTimeMillis()
        return when (period) {
            RetentionPeriod.TWENTY_FOUR_HOURS ->
                now - TimeUnit.HOURS.toMillis(24)
            RetentionPeriod.SEVEN_DAYS ->
                now - TimeUnit.DAYS.toMillis(7)
            RetentionPeriod.OFF ->
                now - TimeUnit.DAYS.toMillis(30) // Max fallback
        }
    }

    private fun isShorterRetention(old: RetentionPeriod, new: RetentionPeriod): Boolean {
        val order = listOf(RetentionPeriod.OFF, RetentionPeriod.SEVEN_DAYS, RetentionPeriod.TWENTY_FOUR_HOURS)
        return order.indexOf(new) > order.indexOf(old)
    }
}
