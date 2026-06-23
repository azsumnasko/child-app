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

    fun dequeueAll(deviceId: String): List<kotlinx.serialization.json.JsonObject> {
        synchronized(lock) {
            val conn = Database.getConnection()
            val messages = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val expiryCutoff = System.currentTimeMillis() - 300_000
            println("[DEQUEUE] deviceId=${deviceId.take(12)} cutoff=$expiryCutoff now=${System.currentTimeMillis()}")
            val selectSql = "SELECT message_json, created_at, message_type FROM signaling_messages WHERE target_device_id = ? ORDER BY id ASC"
            var totalRows = 0
            var expiredCount = 0
            conn.prepareStatement(selectSql).use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        totalRows++
                        val createdAt = rs.getLong("created_at")
                        if (createdAt > expiryCutoff) {
                            val msgJson = rs.getString("message_json")
                            messages.add(json.decodeFromString(msgJson))
                        } else {
                            expiredCount++
                            println("[DEQUEUE] expired row: type=${rs.getString("message_type")} created=$createdAt cutoff=$expiryCutoff diff=${expiryCutoff - createdAt}ms")
                        }
                    }
                }
            }
            println("[DEQUEUE] total=$totalRows expired=$expiredCount returned=${messages.size}")
            if (messages.isNotEmpty()) {
                val deleteSql = "DELETE FROM signaling_messages WHERE target_device_id = ?"
                conn.prepareStatement(deleteSql).use { stmt ->
                    stmt.setString(1, deviceId)
                    val deleted = stmt.executeUpdate()
                    println("[DEQUEUE] deleted $deleted rows")
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
