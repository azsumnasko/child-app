package com.childhelper.app.parent.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.core.common.model.PairingState
import com.childhelper.core.common.util.SafeResult
import com.childhelper.core.network.repository.PairingRepository
import com.childhelper.core.p2p.LocalP2pManager
import com.childhelper.core.p2p.LocalPeerState
import com.childhelper.core.p2p.QrPairingManager
import com.childhelper.core.p2p.PairingQrData
import com.childhelper.core.security.KeystoreManager
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ParentPairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val securePreferences: SecurePreferences,
    private val p2pManager: LocalP2pManager,
    private val qrPairingManager: QrPairingManager,
    private val keystoreManager: KeystoreManager
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

    fun setSessionId(id: String) { _sessionId.value = id }
    fun onCodeChange(newValue: String) { _code.value = newValue }

    fun submitCode() {
        val enteredCode = _code.value.trim().uppercase()
        val sid = _sessionId.value.trim()

        if (enteredCode.length != 6) { _errorMessage.value = "Please enter a 6-character pairing code."; return }
        if (sid.isBlank()) { _errorMessage.value = "Please enter a pairing session ID."; return }

        _pairingState.value = PairingState.GENERATING
        _errorMessage.value = null

        viewModelScope.launch {
            val deviceId = securePreferences.getString("device_id")
                ?: java.util.UUID.randomUUID().toString().also { securePreferences.putString("device_id", it) }
            when (val r = pairingRepository.completePairing(sid, deviceId, enteredCode)) {
                is SafeResult.Success -> _pairingState.value = PairingState.PAIRED
                is SafeResult.Failure -> {
                    _errorMessage.value = r.error
                    _pairingState.value = PairingState.ERROR
                }
            }
        }
    }

    fun startP2pDiscovery() {
        _isP2pMode.value = true
        _pairingState.value = PairingState.GENERATING
        _errorMessage.value = null

        p2pManager.initialize("ParentHelper")
        p2pManager.startDiscovery()

        viewModelScope.launch {
            p2pManager.discoveredPeers.collect { peers ->
                _discoveredDevices.value = peers.map { P2pDevice(it.address, it.name) }
                peers.firstOrNull()?.let { p2pManager.connectToPeer(it) }
            }
        }

        viewModelScope.launch {
            p2pManager.peerFlow.collect { state ->
                when (state) {
                    is LocalPeerState.Connected -> _pairingState.value = PairingState.PAIRED
                    is LocalPeerState.Error -> {
                        _errorMessage.value = state.message
                        _pairingState.value = PairingState.ERROR
                    }
                    else -> {}
                }
            }
        }
    }

    fun onQrCodeScanned(qrContent: String) {
        val data = qrPairingManager.parsePairingData(qrContent)
        if (data == null) {
            _errorMessage.value = "Invalid QR code"
            return
        }
        _isP2pMode.value = true
        _pairingState.value = PairingState.GENERATING
        _sessionId.value = data.deviceId
        startP2pDiscovery()
    }

    fun stopDiscovery() = p2pManager.disconnect()
    fun resetState() {
        _pairingState.value = PairingState.IDLE
        _errorMessage.value = null
        _code.value = ""
        _sessionId.value = ""
        _isP2pMode.value = false
        _discoveredDevices.value = emptyList()
    }
}

data class P2pDevice(val endpointId: String, val name: String)
