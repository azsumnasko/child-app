package com.childhelper.app.parent.ui.liveview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import javax.inject.Inject

/**
 * Connection state for the WebRTC live stream.
 */
enum class LiveConnectionState {
    IDLE,           // Not started
    CONNECTING,     // Establishing peer connection
    SIGNALING,      // Exchanging SDP/ICE
    CONNECTED,      // Stream active
    RECONNECTING,   // Attempting to reconnect
    DISCONNECTED,   // Connection lost
    FAILED,         // Connection failed
    CLOSED          // Manually closed
}

/**
 * Stream mode for the live view.
 */
enum class StreamMode {
    VIDEO_AUDIO,    // Both video and audio
    AUDIO_ONLY,     // Audio only (privacy mode)
    VIDEO_ONLY      // Video only
}

/**
 * Video quality level for adaptive streaming.
 */
enum class VideoQuality {
    LOW,      // 480p or lower — poor network
    MEDIUM,   // 720p — normal conditions
    HIGH      // 1080p — excellent conditions
}

/**
 * UI state for the live view screen.
 */
data class LiveViewUiState(
    val connectionState: LiveConnectionState = LiveConnectionState.IDLE,
    val streamMode: StreamMode = StreamMode.VIDEO_AUDIO,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val isAudioEnabled: Boolean = true,
    val isVideoEnabled: Boolean = true,
    val isTalkBackEnabled: Boolean = false,
    val talkBackAudioLevel: Float = 0f,
    val errorMessage: String? = null,
    val isFullscreen: Boolean = false,
    val connectionDurationMs: Long = 0L,
    val showConnectionDialog: Boolean = false
)

/**
 * ViewModel for the live view screen.
 * Manages WebRTC connection state, stream controls, and talk-back.
 */
@HiltViewModel
class LiveViewViewModel @Inject constructor(
    private val connectionManager: LiveViewConnectionManager,
    private val talkBackManager: TalkBackManager,
    private val securePreferences: SecurePreferences
) : ViewModel() {

    private val _connectionState = MutableStateFlow(LiveConnectionState.IDLE)
    private val _streamMode = MutableStateFlow(StreamMode.VIDEO_AUDIO)
    private val _videoQuality = MutableStateFlow(VideoQuality.MEDIUM)
    private val _isAudioEnabled = MutableStateFlow(true)
    private val _isVideoEnabled = MutableStateFlow(true)
    private val _isFullscreen = MutableStateFlow(false)
    private val _connectionDurationMs = MutableStateFlow(0L)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _showConnectionDialog = MutableStateFlow(false)

    val remoteVideoTrack: StateFlow<VideoTrack?>
        get() = connectionManager.remoteVideoTrack

    val remoteEglBase: EglBase?
        get() = connectionManager.currentEglBase

    init {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                _connectionState.value = state
                if (state == LiveConnectionState.CONNECTED) {
                    startDurationTimer()
                    _showConnectionDialog.value = false
                }
            }
        }
    }

    val uiState: StateFlow<LiveViewUiState> = combine(
        _connectionState,
        _streamMode,
        _videoQuality,
        _isAudioEnabled,
        _isVideoEnabled,
        talkBackManager.isTalkBackEnabled,
        talkBackManager.audioLevel,
        _isFullscreen,
        _connectionDurationMs,
        _errorMessage,
        _showConnectionDialog
    ) { values ->
        LiveViewUiState(
            connectionState = values[0] as LiveConnectionState,
            streamMode = values[1] as StreamMode,
            videoQuality = values[2] as VideoQuality,
            isAudioEnabled = values[3] as Boolean,
            isVideoEnabled = values[4] as Boolean,
            isTalkBackEnabled = values[5] as Boolean,
            talkBackAudioLevel = values[6] as Float,
            isFullscreen = values[7] as Boolean,
            connectionDurationMs = values[8] as Long,
            errorMessage = values[9] as String?,
            showConnectionDialog = values[10] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LiveViewUiState()
    )

    // --- WebRTC state callbacks ---

    fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
        val newState = when (state) {
            PeerConnection.PeerConnectionState.CONNECTING,
            PeerConnection.PeerConnectionState.NEW -> LiveConnectionState.CONNECTING
            PeerConnection.PeerConnectionState.CONNECTED -> LiveConnectionState.CONNECTED
            PeerConnection.PeerConnectionState.DISCONNECTED -> LiveConnectionState.DISCONNECTED
            PeerConnection.PeerConnectionState.FAILED -> LiveConnectionState.FAILED
            PeerConnection.PeerConnectionState.CLOSED -> LiveConnectionState.CLOSED
            else -> LiveConnectionState.IDLE
        }
        _connectionState.value = newState

        if (newState == LiveConnectionState.CONNECTED) {
            startDurationTimer()
            _showConnectionDialog.value = false
        }
    }

    fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                if (_connectionState.value != LiveConnectionState.CONNECTED) {
                    _connectionState.value = LiveConnectionState.CONNECTED
                    startDurationTimer()
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                _connectionState.value = LiveConnectionState.DISCONNECTED
            }
            PeerConnection.IceConnectionState.FAILED -> {
                _connectionState.value = LiveConnectionState.FAILED
            }
            else -> { /* no-op */ }
        }
    }

    // --- Controls ---

    fun toggleAudio() {
        _isAudioEnabled.update { !it }
    }

    fun toggleVideo() {
        _isVideoEnabled.update { !it }
    }

    fun toggleTalkBack() {
        talkBackManager.toggleTalkBack()
    }

    fun setStreamMode(mode: StreamMode) {
        _streamMode.value = mode
        when (mode) {
            StreamMode.VIDEO_AUDIO -> {
                _isAudioEnabled.value = true
                _isVideoEnabled.value = true
            }
            StreamMode.AUDIO_ONLY -> {
                _isAudioEnabled.value = true
                _isVideoEnabled.value = false
            }
            StreamMode.VIDEO_ONLY -> {
                _isAudioEnabled.value = false
                _isVideoEnabled.value = true
            }
        }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _isFullscreen.value = fullscreen
    }

    fun setVideoQuality(quality: VideoQuality) {
        _videoQuality.value = quality
    }

    // --- Connection lifecycle ---

    fun startConnection() {
        if (_connectionState.value == LiveConnectionState.CONNECTING ||
            _connectionState.value == LiveConnectionState.SIGNALING ||
            _connectionState.value == LiveConnectionState.CONNECTED) {
            return // Already connecting or connected
        }
        _connectionState.value = LiveConnectionState.CONNECTING
        _showConnectionDialog.value = true
        _connectionDurationMs.value = 0L
        viewModelScope.launch {
            val childDeviceId = securePreferences.getString("paired_child_device_id", "") ?: ""
            if (childDeviceId.isBlank()) {
                _connectionState.value = LiveConnectionState.FAILED
                _errorMessage.value = "No paired child device found"
                return@launch
            }

            try {
                withTimeout(30_000) {
                    val result = connectionManager.connect(childDeviceId)
                    if (result.isFailure) {
                        _connectionState.value = LiveConnectionState.FAILED
                        _errorMessage.value = result.exceptionOrNull()?.message
                        return@withTimeout
                    }

                    connectionManager.currentPeerConnection?.let { pc ->
                        talkBackManager.initialize(pc, connectionManager.currentDataChannel)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _connectionState.value = LiveConnectionState.FAILED
                _errorMessage.value = "Connection timed out"
                connectionManager.disconnect()
            } catch (e: Exception) {
                _connectionState.value = LiveConnectionState.FAILED
                _errorMessage.value = e.message
                connectionManager.disconnect()
            }
        }
    }

    fun disconnect() {
        talkBackManager.setTalkBackEnabled(false)
        connectionManager.disconnect()
        _connectionState.value = LiveConnectionState.CLOSED
        _connectionDurationMs.value = 0L
        durationJob?.cancel()
    }

    fun retryConnection() {
        _errorMessage.value = null
        _connectionState.value = LiveConnectionState.RECONNECTING
        startConnection()
    }

    fun dismissConnectionDialog() {
        _showConnectionDialog.value = false
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    // --- Duration timer ---

    private var durationJob: kotlinx.coroutines.Job? = null

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis() - _connectionDurationMs.value
            while (true) {
                _connectionDurationMs.value = System.currentTimeMillis() - startTime
                delay(1000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        durationJob?.cancel()
        talkBackManager.release()
        connectionManager.disconnect()
    }
}
