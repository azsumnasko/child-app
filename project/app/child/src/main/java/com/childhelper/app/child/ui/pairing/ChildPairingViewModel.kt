package com.childhelper.app.child.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.core.common.model.PairingState
import com.childhelper.core.common.model.PairingStatus
import com.childhelper.core.common.util.SafeResult
import com.childhelper.core.network.repository.PairingRepository
import com.childhelper.core.p2p.LocalP2pManager
import com.childhelper.core.p2p.LocalPeerState
import com.childhelper.core.p2p.QrPairingManager
import com.childhelper.core.security.KeystoreManager
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChildPairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val securePreferences: SecurePreferences,
    private val p2pManager: LocalP2pManager,
    private val qrPairingManager: QrPairingManager,
    private val keystoreManager: KeystoreManager
) : ViewModel() {

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _pairingState = MutableStateFlow(PairingState.IDLE)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _qrData = MutableStateFlow("")
    val qrData: StateFlow<String> = _qrData.asStateFlow()

    private val _isP2pMode = MutableStateFlow(false)
    val isP2pMode: StateFlow<Boolean> = _isP2pMode.asStateFlow()

    private var currentSessionId: String? = null
    private var pollingJob: Job? = null
    private var p2pJob: Job? = null

    fun startPairing() {
        _isP2pMode.value = false
        _pairingState.value = PairingState.GENERATING
        _errorMessage.value = null

        viewModelScope.launch {
            val deviceId = securePreferences.getString("device_id")
                ?: java.util.UUID.randomUUID().toString().also { id ->
                    securePreferences.putString("device_id", id)
                }

            when (val result = pairingRepository.initiatePairing(deviceId)) {
                is SafeResult.Success -> {
                    currentSessionId = result.data.sessionId
                    _sessionId.value = result.data.sessionId
                    _pairingCode.value = result.data.pairingCode
                    _pairingState.value = PairingState.WAITING
                    pollingJob = viewModelScope.launch {
                        while (true) {
                            delay(2000)
                            val s = pairingRepository.getPairingStatus(_sessionId.value)
                            if (s is SafeResult.Success && s.data.status == PairingStatus.COMPLETED) {
                                _pairingState.value = PairingState.PAIRED
                                return@launch
                            }
                        }
                    }
                }
                is SafeResult.Failure -> {
                    _errorMessage.value = result.error
                    _pairingState.value = PairingState.ERROR
                }
            }
        }
    }

    fun startP2pPairing() {
        _isP2pMode.value = true
        _pairingState.value = PairingState.GENERATING
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val deviceId = securePreferences.getString("device_id")
                    ?: java.util.UUID.randomUUID().toString().also { id ->
                        securePreferences.putString("device_id", id)
                    }

                // Generate key pair for P2P identity
                val keyPair = keystoreManager.generateKeyPair("child_p2p_key_$deviceId")
                val publicKey = keyPair.public.encoded?.let {
                    java.util.Base64.getEncoder().encodeToString(it)
                } ?: ""

                // Generate QR data
                val qrContent = qrPairingManager.generatePairingData(
                    deviceId = deviceId,
                    deviceName = "ChildHelper",
                    publicKey = publicKey
                )
                _qrData.value = qrContent
                _sessionId.value = deviceId

                // Start WiFi Direct discovery
                p2pManager.initialize("ChildHelper-$deviceId".take(20))
                p2pManager.startDiscovery()

                // Show QR code while waiting for parent
                _pairingState.value = PairingState.WAITING

                // Monitor connection state
                p2pJob = launch {
                    p2pManager.peerFlow.collect { state ->
                        when (state) {
                            is LocalPeerState.Connected -> {
                                _pairingState.value = PairingState.PAIRED
                            }
                            is LocalPeerState.Error -> {
                                _errorMessage.value = state.message
                                _pairingState.value = PairingState.ERROR
                            }
                            else -> {}
                        }
                    }
                }

                launch {
                    p2pManager.discoveredPeers.collect { peers ->
                        peers.firstOrNull()?.let { p2pManager.connectToPeer(it) }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "P2P pairing failed"
                _pairingState.value = PairingState.ERROR
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        p2pJob?.cancel()
    }

    fun cancelPairing() {
        pollingJob?.cancel()
        pollingJob = null
        p2pJob?.cancel()
        p2pJob = null

        if (_isP2pMode.value) p2pManager.disconnect()

        _pairingState.value = PairingState.IDLE
        _pairingCode.value = ""
        _qrData.value = ""
        _errorMessage.value = null

        if (!_isP2pMode.value) {
            val sid = currentSessionId ?: return
            viewModelScope.launch {
                val deviceId = securePreferences.getString("device_id") ?: return@launch
                pairingRepository.cancelPairing(sid, deviceId)
            }
        }
    }

    fun resetState() {
        _pairingState.value = PairingState.IDLE
        _pairingCode.value = ""
        _sessionId.value = ""
        _qrData.value = ""
        _errorMessage.value = null
        currentSessionId = null
        _isP2pMode.value = false
    }
}
