package com.childhelper.core.p2p

import com.childhelper.core.security.PairingCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class QrPairingManager(
    private val pairingCrypto: PairingCrypto
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun generatePairingData(deviceId: String, deviceName: String, publicKey: String): String {
        return json.encodeToString(PairingQrData(
            deviceId = deviceId, deviceName = deviceName,
            publicKey = publicKey, version = 1
        ))
    }

    fun parsePairingData(qrContent: String): PairingQrData? {
        return try { json.decodeFromString<PairingQrData>(qrContent) } catch (_: Exception) { null }
    }
}

@Serializable
data class PairingQrData(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val version: Int = 1
)
