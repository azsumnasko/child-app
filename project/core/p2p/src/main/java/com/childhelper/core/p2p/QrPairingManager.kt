package com.childhelper.core.p2p

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class QrPairingManager {
    private val json = Json { ignoreUnknownKeys = true }

    /** Generate P2P pairing QR */
    fun generateP2pData(deviceId: String, deviceName: String, publicKey: String): String {
        return json.encodeToString(PairingQrData(
            deviceId = deviceId, deviceName = deviceName,
            publicKey = publicKey, version = 1
        ))
    }

    /** Generate server-based pairing QR (contains server URL + session ID + code) */
    fun generateServerData(serverUrl: String, sessionId: String, code: String): String {
        return json.encodeToString(ServerPairingQrData(
            server = serverUrl, sessionId = sessionId, code = code
        ))
    }

    /** Parse any QR data. Returns the appropriate subtype or null. */
    fun parseQrData(qrContent: String): QrScanResult? {
        return try {
            val obj = json.decodeFromString<kotlinx.serialization.json.JsonObject>(qrContent)
            when {
                obj.containsKey("server") -> {
                    val data = json.decodeFromString<ServerPairingQrData>(qrContent)
                    QrScanResult.ServerPairing(data.server, data.sessionId, data.code)
                }
                obj.containsKey("deviceId") -> {
                    val data = json.decodeFromString<PairingQrData>(qrContent)
                    QrScanResult.P2pPairing(data.deviceId, data.deviceName, data.publicKey)
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Parse P2P data only (backward compat) */
    fun parsePairingData(qrContent: String): PairingQrData? {
        return try { json.decodeFromString<PairingQrData>(qrContent) } catch (_: Exception) { null }
    }
}

@Serializable data class PairingQrData(val deviceId: String, val deviceName: String, val publicKey: String, val version: Int = 1)
@Serializable data class ServerPairingQrData(val server: String, val sessionId: String, val code: String)

sealed class QrScanResult {
    data class ServerPairing(val server: String, val sessionId: String, val code: String) : QrScanResult()
    data class P2pPairing(val deviceId: String, val deviceName: String, val publicKey: String) : QrScanResult()
}
