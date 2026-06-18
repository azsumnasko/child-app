package com.childhelper.app.parent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for alert history operations.
 * All queries are privacy-safe: only metadata, no media content.
 */
@Dao
interface AlertDao {

    /**
     * Insert a new alert entity. Uses REPLACE for idempotency.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEntity): Long

    /**
     * Insert multiple alerts in a batch.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alerts: List<AlertEntity>)

    /**
     * Get all alerts ordered by timestamp descending (newest first).
     */
    @Query("SELECT * FROM alerts ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<AlertEntity>>

    /**
     * Get alerts filtered by event type.
     */
    @Query("SELECT * FROM alerts WHERE eventType = :eventType ORDER BY timestamp DESC")
    fun getAlertsByType(eventType: String): Flow<List<AlertEntity>>

    /**
     * Get alerts within a date range.
     */
    @Query(
        "SELECT * FROM alerts WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC"
    )
    fun getAlertsByDateRange(startTime: Long, endTime: Long): Flow<List<AlertEntity>>

    /**
     * Get alerts newer than the given timestamp.
     */
    @Query("SELECT * FROM alerts WHERE timestamp > :since ORDER BY timestamp DESC")
    fun getAlertsSince(since: Long): Flow<List<AlertEntity>>

    /**
     * Get the most recent N alerts.
     */
    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentAlerts(limit: Int): Flow<List<AlertEntity>>

    /**
     * Count total alerts.
     */
    @Query("SELECT COUNT(*) FROM alerts")
    fun getAlertCount(): Flow<Int>

    /**
     * Count alerts by type.
     */
    @Query("SELECT COUNT(*) FROM alerts WHERE eventType = :eventType")
    fun getAlertCountByType(eventType: String): Flow<Int>

    /**
     * Delete alerts older than the given timestamp (retention cleanup).
     */
    @Query("DELETE FROM alerts WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    /**
     * Delete all alerts — used for data deletion flow.
     */
    @Query("DELETE FROM alerts")
    suspend fun deleteAll(): Int

    /**
     * Delete a specific alert by ID.
     */
    @Query("DELETE FROM alerts WHERE id = :alertId")
    suspend fun deleteById(alertId: String): Int
}
