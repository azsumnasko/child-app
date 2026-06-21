package com.childhelper.server.store

import com.childhelper.core.common.signaling.SignalingMessage
import kotlinx.serialization.json.Json

class MessageStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun enqueue(toDeviceId: String, message: SignalingMessage) {
        val messageJson = json.encodeToString(SignalingMessage.serializer(), message)
        val messageType = message::class.simpleName ?: "unknown"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "INSERT INTO signaling_messages(target_device_id, message_type, message_json, created_at) VALUES(?,?,?,?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, toDeviceId)
                stmt.setString(2, messageType)
                stmt.setString(3, messageJson)
                stmt.setLong(4, now)
                stmt.executeUpdate()
            }
        }
    }

    fun dequeueAll(deviceId: String): List<SignalingMessage> {
        synchronized(lock) {
            val conn = Database.getConnection()
            val messages = mutableListOf<SignalingMessage>()
            val selectSql = "SELECT message_json FROM signaling_messages WHERE target_device_id = ? ORDER BY id ASC"
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val msgJson = rs.getString("message_json")
                        messages.add(json.decodeFromString(SignalingMessage.serializer(), msgJson))
                    }
                }
            }
            if (messages.isNotEmpty()) {
                val deleteSql = "DELETE FROM signaling_messages WHERE target_device_id = ?"
                conn.prepareStatement(deleteSql).use { stmt ->
                    stmt.setString(1, deviceId)
                    stmt.executeUpdate()
                }
            }
            return messages
        }
    }

    fun peekAll(deviceId: String): List<SignalingMessage> {
        synchronized(lock) {
            val conn = Database.getConnection()
            val messages = mutableListOf<SignalingMessage>()
            val sql = "SELECT message_json FROM signaling_messages WHERE target_device_id = ? ORDER BY id ASC"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val msgJson = rs.getString("message_json")
                        messages.add(json.decodeFromString(SignalingMessage.serializer(), msgJson))
                    }
                }
            }
            return messages
        }
    }
}
