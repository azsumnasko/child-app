package com.childhelper.app.parent.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.core.common.model.PairingState
import com.childhelper.core.common.util.CryptoUtil
import com.childhelper.core.common.util.SafeResult
import com.childhelper.core.network.di.DynamicBaseUrlInterceptor
import com.childhelper.core.network.repository.PairingRepository
import com.childhelper.core.p2p.LocalP2pManager
import com.childhelper.core.p2p.LocalPeerState
import com.childhelper.core.p2p.P2pMessage
import com.childhelper.core.p2p.P2pMessageType
import com.childhelper.core.p2p.QrPairingManager
import com.childhelper.core.p2p.QrScanResult
import com.childhelper.core.security.KeystoreManager
import com.childhelper.core.security.PairingCrypto
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyPair
import javax.inject.Inject

@HiltViewModel
class ParentPairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val securePreferences: SecurePreferences,
    private val p2pManager: LocalP2pManager,
    private val qrPairingManager: QrPairingManager,
    private val keystoreManager: KeystoreManager,
    private val pairingCrypto: PairingCrypto,
    private val dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor
) : ViewModel() {

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    private val _pairingState = MutableStateFlow(PairingState.IDLE)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _isP2pMode = MutableStateFlow(false)
    val isP2pMode: StateFlow<Boolean> = _isP2pMode.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<P2pDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<P2pDevice>> = _discoveredDevices.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var parentEcdhKeyPair: KeyPair? = null
    private var p2pChildPublicKeyB64: String? = null
    private var p2pChildDeviceId: String? = null

    fun onCodeChange(newValue: String) { _code.value = newValue }
    fun setSessionId(id: String) { _sessionId.value = id }

    /** Try code-only first (new server), fall back to session+code (old server) */
    fun submitCode() {
        val enteredCode = _code.value.replace(" ", "").uppercase().take(6)
        if (enteredCode.length != 6) { _errorMessage.value = "Enter the 6-character code shown on the child app."; return }

        _pairingState.value = PairingState.GENERATING
        _errorMessage.value = null

        viewModelScope.launch {
            val deviceId = securePreferences.getString("device_id")
                ?: java.util.UUID.randomUUID().toString().also { securePreferences.putString("device_id", it) }

            // Try new code-only endpoint first
            val result = pairingRepository.completeByCode(deviceId, enteredCode)
            when (result) {
                is SafeResult.Success -> { _pairingState.value = PairingState.PAIRED; return@launch }
                is SafeResult.Failure -> {
                    // If server lacks the new endpoint, fall back to session+code
                    val sid = _sessionId.value.trim()
                    if (sid.isNotBlank()) {
                        when (val r2 = pairingRepository.completePairing(sid, deviceId, enteredCode)) {
                            is SafeResult.Success -> _pairingState.value = PairingState.PAIRED
                            is SafeResult.Failure -> {
                                _errorMessage.value = r2.error.ifBlank { "Wrong code or session expired. Try generating a new one." }
                                _pairingState.value = PairingState.ERROR
                            }
                        }
                    } else {
                        _errorMessage.value = "You also need the Session ID shown on the child app."
                        _pairingState.value = PairingState.ERROR
                    }
                }
            }
        }
    }

    fun startP2pDiscovery() {
        _isP2pMode.value = true; _pairingState.value = PairingState.GENERATING; _errorMessage.value = null
        val name = p2pChildDeviceId?.let { "Parent-${it.take(12)}" } ?: "ParentHelper"
        p2pManager.initialize(name); p2pManager.startDiscovery()
        viewModelScope.launch {
            p2pManager.discoveredPeers.collect { peers ->
                _discoveredDevices.value = peers.map { P2pDevice(it.address, it.name) }
                peers.firstOrNull()?.let { p2pManager.connectToPeer(it) }
            }
        }
        viewModelScope.launch {
            p2pManager.peerFlow.collect { state ->
                when (state) {
                    is LocalPeerState.Connected -> {
                        _pairingState.value = PairingState.PAIRED
                        performP2pKeyExchange()
                    }
                    is LocalPeerState.Error -> { _errorMessage.value = state.message; _pairingState.value = PairingState.ERROR }
                    else -> {}
                }
            }
        }
    }

    private fun performP2pKeyExchange() {
        val childKeyB64 = p2pChildPublicKeyB64 ?: return
        val ecdhKeyPair = parentEcdhKeyPair ?: return
        viewModelScope.launch {
            try {
                val parentPublicKeyB64 = CryptoUtil.base64Encode(ecdhKeyPair.public.encoded)
                val keyPayload = buildJsonObject { put("publicKey", parentPublicKeyB64) }
                p2pManager.sendMessage(P2pMessage(P2pMessageType.KEY_EXCHANGE, json.encodeToString(keyPayload)))

                p2pManager.messageFlow.collect { msg ->
                    if (msg.type == P2pMessageType.KEY_EXCHANGE) {
                        val obj = json.decodeFromString<JsonObject>(msg.payload)
                        val childEcdhPublicKeyB64 = obj["publicKey"]?.jsonPrimitive?.content ?: return@collect
                        val childEcdhPublicKeyBytes = CryptoUtil.base64Decode(childEcdhPublicKeyB64)
                        val keyFactory = java.security.KeyFactory.getInstance("EC")
                        val publicKeySpec = java.security.spec.X509EncodedKeySpec(childEcdhPublicKeyBytes)
                        val childPublicKey = keyFactory.generatePublic(publicKeySpec)
                        val sharedSecret = pairingCrypto.deriveSharedSecret(ecdhKeyPair, childPublicKey)
                        securePreferences.putString("shared_secret", CryptoUtil.base64Encode(sharedSecret))
                        securePreferences.putBoolean("is_paired", true)
                        return@collect
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun onQrCodeScanned(qrContent: String) {
        when (val result = qrPairingManager.parseQrData(qrContent)) {
            is QrScanResult.ServerPairing -> {
                _isP2pMode.value = false
                _sessionId.value = result.sessionId
                _code.value = result.code
                dynamicBaseUrlInterceptor.baseUrl = result.server
                submitCode()
            }
            is QrScanResult.P2pPairing -> {
                _isP2pMode.value = true
                _pairingState.value = PairingState.GENERATING
                p2pChildPublicKeyB64 = result.publicKey
                p2pChildDeviceId = result.deviceId
                parentEcdhKeyPair = pairingCrypto.generateEcdhKeyPair()
                startP2pDiscovery()
            }
            null -> _errorMessage.value = "Invalid QR code"
        }
    }

    fun stopDiscovery() = p2pManager.disconnect()
    fun resetState() {
        _pairingState.value = PairingState.IDLE; _errorMessage.value = null
        _code.value = ""; _sessionId.value = ""; _isP2pMode.value = false
        _discoveredDevices.value = emptyList()
        parentEcdhKeyPair = null; p2pChildPublicKeyB64 = null; p2pChildDeviceId = null
    }
}

data class P2pDevice(val endpointId: String, val name: String)
