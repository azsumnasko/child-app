package com.childhelper.core.network.repository

import com.childhelper.core.common.model.PairingSession
import com.childhelper.core.common.model.PairingStatus
import com.childhelper.core.common.util.CryptoUtil
import com.childhelper.core.common.util.ErrorCode
import com.childhelper.core.common.util.SafeResult
import com.childhelper.core.common.util.safeCallAsync
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.model.CompletePairingRequest
import com.childhelper.core.network.model.InitiatePairingRequest
import com.childhelper.core.network.model.RevokePairingRequest
import com.childhelper.core.security.PairingCrypto
import com.childhelper.core.security.SecurePreferences
import kotlinx.serialization.json.Json
import java.security.KeyPair
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepository @Inject constructor(
    private val pairingApi: PairingApi,
    private val pairingCrypto: PairingCrypto,
    private val securePreferences: SecurePreferences
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var childKeyPair: KeyPair? = null

    suspend fun initiatePairing(deviceId: String): SafeResult<PairingSession> {
        val keyPair = pairingCrypto.generateEcdhKeyPair()
        val publicKeyB64 = CryptoUtil.base64Encode(keyPair.public.encoded)
        val request = InitiatePairingRequest(
            childDeviceId = deviceId,
            childPublicKey = publicKeyB64
        )

        val result = safeCallAsync(ErrorCode.NETWORK_ERROR) {
            pairingApi.initiatePairing(request)
        }

        return when (result) {
            is SafeResult.Success -> {
                storeSession(result.data)
                SafeResult.Success(result.data)
            }
            is SafeResult.Failure -> {
                localInitiateFallback(deviceId, keyPair)
            }
        }
    }

    suspend fun completePairing(
        sessionId: String,
        parentDeviceId: String,
        code: String
    ): SafeResult<PairingSession> {
        val parentKeyPair = pairingCrypto.generateEcdhKeyPair()
        val parentPublicKeyB64 = CryptoUtil.base64Encode(parentKeyPair.public.encoded)
        val request = CompletePairingRequest(
            sessionId = sessionId,
            parentDeviceId = parentDeviceId,
            parentPublicKey = parentPublicKeyB64,
            pairingCode = code
        )

        val result = safeCallAsync(ErrorCode.NETWORK_ERROR) {
            pairingApi.completePairing(request)
        }

        return when (result) {
            is SafeResult.Success -> {
                val session = result.data
                val childPublicKeyB64 = session.childPublicKey ?: return SafeResult.Failure("Server did not return child public key. Pairing incomplete.", ErrorCode.PAIRING_ERROR)
                deriveAndStoreSharedSecret(parentKeyPair, childPublicKeyB64)
                storeSession(session)
                securePreferences.putString("device_id", parentDeviceId)
                securePreferences.putBoolean("is_paired", true)
                SafeResult.Success(session)
            }
            is SafeResult.Failure -> {
                localCompleteFallback(sessionId, parentDeviceId, code, parentKeyPair)
            }
        }
    }

    suspend fun getPairingStatus(sessionId: String): SafeResult<PairingSession> {
        val result = safeCallAsync(ErrorCode.NETWORK_ERROR) {
            pairingApi.getPairingStatus(sessionId)
        }

        return when (result) {
            is SafeResult.Success -> {
                storeSession(result.data)
                SafeResult.Success(result.data)
            }
            is SafeResult.Failure -> {
                val stored = loadSession(sessionId)
                if (stored != null) {
                    SafeResult.Success(stored)
                } else {
                    result
                }
            }
        }
    }

    suspend fun cancelPairing(sessionId: String, deviceId: String): SafeResult<Unit> {
        val request = RevokePairingRequest(
            sessionId = sessionId,
            deviceId = deviceId
        )

        val result = safeCallAsync(ErrorCode.NETWORK_ERROR) {
            pairingApi.revokePairing(request)
        }

        securePreferences.remove("pairing_session_$sessionId")

        return when (result) {
            is SafeResult.Success -> result
            is SafeResult.Failure -> {
                SafeResult.Success(Unit)
            }
        }
    }

    suspend fun getStoredSession(sessionId: String): PairingSession? {
        return loadSession(sessionId)
    }

    private suspend fun localInitiateFallback(
        deviceId: String,
        keyPair: KeyPair
    ): SafeResult<PairingSession> {
        val sessionId = UUID.randomUUID().toString()
        val code = pairingCrypto.generatePairingCode()
        val publicKeyB64 = CryptoUtil.base64Encode(keyPair.public.encoded)

        val session = PairingSession(
            sessionId = sessionId,
            pairingCode = code,
            childDeviceId = deviceId,
            childPublicKey = publicKeyB64,
            status = PairingStatus.PENDING
        )

        storeSession(session)
        storeKeyPair(sessionId, keyPair)

        return SafeResult.Success(session)
    }

    private suspend fun localCompleteFallback(
        sessionId: String,
        parentDeviceId: String,
        code: String,
        parentKeyPair: KeyPair
    ): SafeResult<PairingSession> {
        val stored = loadSession(sessionId)
            ?: return SafeResult.Failure(
                "Pairing session not found. Please check the session ID.",
                ErrorCode.PAIRING_ERROR
            )

        if (stored.pairingCode != code) {
            return SafeResult.Failure(
                "Invalid pairing code. Please check and try again.",
                ErrorCode.PAIRING_ERROR
            )
        }

        if (stored.status == PairingStatus.EXPIRED || stored.status == PairingStatus.REVOKED) {
            return SafeResult.Failure(
                "This pairing session has expired or been revoked.",
                ErrorCode.PAIRING_ERROR
            )
        }

        if (System.currentTimeMillis() > stored.expiresAt) {
            return SafeResult.Failure(
                "This pairing code has expired. Generate a new code on the child device.",
                ErrorCode.PAIRING_ERROR
            )
        }

        val childPublicKeyB64 = stored.childPublicKey
        if (childPublicKeyB64 != null) {
            deriveAndStoreSharedSecret(parentKeyPair, childPublicKeyB64)
        } else {
            return SafeResult.Failure("Cannot complete pairing without ECDH key exchange. Server connection required.", ErrorCode.PAIRING_ERROR)
        }

        val completedSession = stored.copy(
            parentDeviceId = parentDeviceId,
            parentPublicKey = CryptoUtil.base64Encode(parentKeyPair.public.encoded),
            status = PairingStatus.COMPLETED
        )

        storeSession(completedSession)
        securePreferences.putString("device_id", parentDeviceId)
        securePreferences.putBoolean("is_paired", true)

        return SafeResult.Success(completedSession)
    }

    private suspend fun deriveAndStoreSharedSecret(
        keyPair: KeyPair,
        remotePublicKeyB64: String
    ) {
        val remotePublicKeyBytes = CryptoUtil.base64Decode(remotePublicKeyB64)
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val publicKeySpec = java.security.spec.X509EncodedKeySpec(remotePublicKeyBytes)
        val remotePublicKey = keyFactory.generatePublic(publicKeySpec)
        val sharedSecret = pairingCrypto.deriveSharedSecret(keyPair, remotePublicKey)
        securePreferences.putString("shared_secret", CryptoUtil.base64Encode(sharedSecret))
        securePreferences.putBoolean("is_paired", true)
    }

    private suspend fun storeSession(session: PairingSession) {
        securePreferences.putString("pairing_session_${session.sessionId}", json.encodeToString(PairingSession.serializer(), session))
    }

    private suspend fun loadSession(sessionId: String): PairingSession? {
        val raw = securePreferences.getString("pairing_session_$sessionId") ?: return null
        return try {
            json.decodeFromString(PairingSession.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun storeKeyPair(sessionId: String, keyPair: KeyPair) {
        childKeyPair = keyPair
    }
}
