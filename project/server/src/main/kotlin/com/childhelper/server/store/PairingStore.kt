package com.childhelper.server.store

import com.childhelper.core.common.model.PairingSession
import com.childhelper.core.common.model.PairingStatus
import java.util.UUID
import kotlin.random.Random

class PairingStore {
    private val lock = Any()

    fun createSession(childDeviceId: String, childPublicKey: String): PairingSession {
        val sessionId = UUID.randomUUID().toString()
        val code = generatePairingCode()
        val now = System.currentTimeMillis()

        val session = PairingSession(
            sessionId = sessionId, pairingCode = code,
            childDeviceId = childDeviceId, childPublicKey = childPublicKey,
            status = PairingStatus.PENDING, createdAt = now,
            expiresAt = now + 5 * 60 * 1000
        )

        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "INSERT INTO pairing_sessions(session_id, pairing_code, child_device_id, child_public_key, status, created_at, expires_at) VALUES(?,?,?,?,?,?,?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, session.sessionId)
                stmt.setString(2, session.pairingCode)
                stmt.setString(3, session.childDeviceId)
                stmt.setString(4, session.childPublicKey)
                stmt.setString(5, session.status.name)
                stmt.setLong(6, session.createdAt)
                stmt.setLong(7, session.expiresAt)
                stmt.executeUpdate()
            }
        }
        return session
    }

    fun getSession(sessionId: String): PairingSession? {
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "SELECT * FROM pairing_sessions WHERE session_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, sessionId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val session = rowToSession(rs)
                    if (System.currentTimeMillis() > session.expiresAt && session.status == PairingStatus.PENDING) {
                        val expired = session.copy(status = PairingStatus.EXPIRED)
                        val updateSql = "UPDATE pairing_sessions SET status = ? WHERE session_id = ?"
                        conn.prepareStatement(updateSql).use { updateStmt ->
                            updateStmt.setString(1, PairingStatus.EXPIRED.name)
                            updateStmt.setString(2, sessionId)
                            updateStmt.executeUpdate()
                        }
                        return expired
                    }
                    return session
                }
            }
        }
    }

    fun completeByCode(pairingCode: String, parentDeviceId: String, parentPublicKey: String): PairingSession? {
        val code = pairingCode.uppercase().trim()
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "SELECT session_id FROM pairing_sessions WHERE pairing_code = ? AND status = ? ORDER BY created_at DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, code)
                stmt.setString(2, PairingStatus.PENDING.name)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val sessionId = rs.getString("session_id")
                    return completePairing(sessionId, parentDeviceId, parentPublicKey, pairingCode)
                }
            }
        }
    }

    fun completePairing(sessionId: String, parentDeviceId: String, parentPublicKey: String, pairingCode: String): PairingSession? {
        synchronized(lock) {
            val conn = Database.getConnection()
            val session = getSessionInternal(conn, sessionId) ?: return null
            if (session.status != PairingStatus.PENDING) return null
            if (session.pairingCode != pairingCode.uppercase().trim()) return null
            val completed = session.copy(
                parentDeviceId = parentDeviceId, parentPublicKey = parentPublicKey,
                status = PairingStatus.COMPLETED
            )
            val sql = "UPDATE pairing_sessions SET parent_device_id = ?, parent_public_key = ?, status = ? WHERE session_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, parentDeviceId)
                stmt.setString(2, parentPublicKey)
                stmt.setString(3, PairingStatus.COMPLETED.name)
                stmt.setString(4, sessionId)
                stmt.executeUpdate()
            }
            return completed
        }
    }

    fun revokeSession(sessionId: String): Boolean {
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "UPDATE pairing_sessions SET status = ? WHERE session_id = ? AND status NOT IN (?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, PairingStatus.REVOKED.name)
                stmt.setString(2, sessionId)
                stmt.setString(3, PairingStatus.REVOKED.name)
                stmt.setString(4, PairingStatus.EXPIRED.name)
                val updated = stmt.executeUpdate()
                return updated > 0
            }
        }
    }

    fun getParentDeviceId(childDeviceId: String): String? {
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "SELECT parent_device_id, status FROM pairing_sessions WHERE child_device_id = ? ORDER BY created_at DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, childDeviceId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString("parent_device_id")
                }
            }
        }
    }

    fun getChildDeviceId(parentDeviceId: String): String? {
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "SELECT child_device_id FROM pairing_sessions WHERE parent_device_id = ? ORDER BY created_at DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, parentDeviceId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString("child_device_id")
                }
            }
        }
    }

    fun arePaired(deviceId1: String, deviceId2: String): Boolean {
        if (deviceId1 == deviceId2) return false // A device cannot be paired with itself
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "SELECT * FROM pairing_sessions WHERE (child_device_id = ? OR parent_device_id = ?) AND status = ? ORDER BY created_at DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, deviceId1)
                stmt.setString(2, deviceId1)
                stmt.setString(3, PairingStatus.COMPLETED.name)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return false
                    val childDeviceId = rs.getString("child_device_id")
                    val parentDeviceId = rs.getString("parent_device_id")
                    return childDeviceId == deviceId2 || parentDeviceId == deviceId2
                }
            }
        }
    }

    private fun getSessionInternal(conn: java.sql.Connection, sessionId: String): PairingSession? {
        val sql = "SELECT * FROM pairing_sessions WHERE session_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, sessionId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return rowToSession(rs)
            }
        }
    }

    private fun rowToSession(rs: java.sql.ResultSet): PairingSession {
        return PairingSession(
            sessionId = rs.getString("session_id"),
            pairingCode = rs.getString("pairing_code"),
            childDeviceId = rs.getString("child_device_id"),
            parentDeviceId = rs.getString("parent_device_id"),
            childPublicKey = rs.getString("child_public_key"),
            parentPublicKey = rs.getString("parent_public_key"),
            status = PairingStatus.valueOf(rs.getString("status")),
            createdAt = rs.getLong("created_at"),
            expiresAt = rs.getLong("expires_at"),
            parentPhoneNumber = try { rs.getString("parent_phone_number") } catch (_: Exception) { null },
            parentDisplayName = try { rs.getString("parent_display_name") } catch (_: Exception) { null }
        )
    }

    fun updateParentInfo(parentDeviceId: String, phoneNumber: String?, displayName: String?) {
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "UPDATE pairing_sessions SET parent_phone_number = ?, parent_display_name = ? WHERE parent_device_id = ? AND status = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, phoneNumber)
                stmt.setString(2, displayName)
                stmt.setString(3, parentDeviceId)
                stmt.setString(4, PairingStatus.COMPLETED.name)
                stmt.executeUpdate()
            }
        }
    }

    fun getParentInfo(parentDeviceId: String): Pair<String?, String?> {
        synchronized(lock) {
            val conn = Database.getConnection()
            val sql = "SELECT parent_phone_number, parent_display_name FROM pairing_sessions WHERE parent_device_id = ? AND status = ? ORDER BY created_at DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, parentDeviceId)
                stmt.setString(2, PairingStatus.COMPLETED.name)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return Pair(null, null)
                    return Pair(rs.getString("parent_phone_number"), rs.getString("parent_display_name"))
                }
            }
        }
    }

    private fun generatePairingCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
