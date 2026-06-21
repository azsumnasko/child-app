package com.childhelper.server.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class AlertStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun store(parentDeviceId: String, childDeviceId: String, payload: JsonObject) {
        val payloadJson = json.encodeToString(JsonObject.serializer(), payload)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "INSERT INTO pending_alerts(parent_device_id, child_device_id, payload_json, created_at, delivered) VALUES(?,?,?,?,0)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, parentDeviceId)
                stmt.setString(2, childDeviceId)
                stmt.setString(3, payloadJson)
                stmt.setLong(4, now)
                stmt.executeUpdate()
            }
        }
    }

    fun dequeueAll(parentDeviceId: String): List<JsonObject> {
        synchronized(lock) {
            val conn = Database.getConnection()
            val alerts = mutableListOf<JsonObject>()
            val selectSql = "SELECT payload_json FROM pending_alerts WHERE parent_device_id = ? AND delivered = 0 ORDER BY id ASC"
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setString(1, parentDeviceId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val payloadJson = rs.getString("payload_json")
                        alerts.add(json.decodeFromString(JsonObject.serializer(), payloadJson))
                    }
                }
            }
            if (alerts.isNotEmpty()) {
                val markSql = "UPDATE pending_alerts SET delivered = 1 WHERE parent_device_id = ? AND delivered = 0"
                conn.prepareStatement(markSql).use { stmt ->
                    stmt.setString(1, parentDeviceId)
                    stmt.executeUpdate()
                }
            }
            return alerts
        }
    }
}
