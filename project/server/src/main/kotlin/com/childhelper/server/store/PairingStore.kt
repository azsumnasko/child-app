package com.childhelper.server.store

import com.childhelper.core.common.model.PairingSession
import com.childhelper.core.common.model.PairingStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class PairingStore {
    private val sessions = ConcurrentHashMap<String, PairingSession>()
    private val deviceToSession = ConcurrentHashMap<String, String>()

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
        sessions[sessionId] = session
        deviceToSession[childDeviceId] = sessionId
        return session
    }

    fun getSession(sessionId: String): PairingSession? {
        val session = sessions[sessionId] ?: return null
        if (System.currentTimeMillis() > session.expiresAt && session.status == PairingStatus.PENDING) {
            val expired = session.copy(status = PairingStatus.EXPIRED)
            sessions[sessionId] = expired
            return expired
        }
        return session
    }

    fun completePairing(sessionId: String, parentDeviceId: String, parentPublicKey: String): PairingSession? {
        val session = getSession(sessionId) ?: return null
        if (session.status != PairingStatus.PENDING) return null
        val completed = session.copy(
            parentDeviceId = parentDeviceId, parentPublicKey = parentPublicKey,
            status = PairingStatus.COMPLETED
        )
        sessions[sessionId] = completed
        deviceToSession[parentDeviceId] = sessionId
        return completed
    }

    fun revokeSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        sessions[sessionId] = session.copy(status = PairingStatus.REVOKED)
        deviceToSession.remove(session.childDeviceId)
        session.parentDeviceId?.let { deviceToSession.remove(it) }
        return true
    }

    fun getParentDeviceId(childDeviceId: String): String? =
        getSessionForDevice(childDeviceId)?.parentDeviceId

    fun getChildDeviceId(parentDeviceId: String): String? =
        getSessionForDevice(parentDeviceId)?.childDeviceId

    fun arePaired(deviceId1: String, deviceId2: String): Boolean {
        val session = getSessionForDevice(deviceId1) ?: return false
        if (session.status != PairingStatus.COMPLETED) return false
        return session.childDeviceId == deviceId2 || session.parentDeviceId == deviceId2
    }

    private fun getSessionForDevice(deviceId: String): PairingSession? {
        val sessionId = deviceToSession[deviceId] ?: return null
        return sessions[sessionId]
    }

    private fun generatePairingCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
