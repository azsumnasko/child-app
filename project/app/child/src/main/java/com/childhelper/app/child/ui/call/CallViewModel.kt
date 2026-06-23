package com.childhelper.app.child.ui.call

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.app.child.ui.bedtime.VoicePromptManager
import com.childhelper.app.child.R
import com.childhelper.core.common.model.CallSession
import com.childhelper.core.common.model.Contact
import com.childhelper.core.common.model.ContactRole
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import javax.inject.Inject

/**
 * ViewModel for the Call screen.
 * Manages call state, timer, mute/video toggles, and voice prompts.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    application: Application,
    private val callManager: CallManager,
    private val voicePromptManager: VoicePromptManager,
    private val securePreferences: SecurePreferences
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<CallNavigationEvent?>(null)
    val navigationEvent: StateFlow<CallNavigationEvent?> = _navigationEvent.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var callTimerJob: Job? = null
    private var callDurationSeconds = 0

    private val jobs = mutableListOf<Job>()

    init {
        // Observe call manager state
        jobs += viewModelScope.launch {
            callManager.callState.collect { state ->
                handleCallStateChange(state)
            }
        }

        // Observe remote video
        jobs += viewModelScope.launch {
            callManager.remoteVideoTrack.collect { track ->
                _uiState.update { it.copy(remoteVideoTrack = track) }
            }
        }

        // Observe audio-only mode
        jobs += viewModelScope.launch {
            callManager.isAudioOnly.collect { isAudioOnly ->
                _uiState.update { it.copy(isAudioOnly = isAudioOnly) }
            }
        }

        voicePromptManager.initialize()
    }

    fun startCall(contactId: String, hasVideo: Boolean, contactName: String = "") {
        viewModelScope.launch {
            try {
                val displayName = contactName.ifBlank { getContactName(contactId) }
                _uiState.update {
                    it.copy(
                        contactName = displayName,
                        contactId = contactId,
                        hasVideo = hasVideo,
                        status = CallStatusUi.CONNECTING
                    )
                }

                voicePromptManager.speakCallStatus(getApplication<Application>().getString(R.string.call_voice_calling, displayName))

                callManager.initializeWebRtc()
                callManager.initiateCall(contactId, hasVideo)
            } catch (e: Exception) {
                _uiState.update { it.copy(status = CallStatusUi.ERROR, errorMessage = e.message) }
                voicePromptManager.speakCallStatus(getApplication<Application>().getString(R.string.call_voice_error, "Call failed"))
            }
        }
    }

    fun acceptCall(sessionId: String) {
        callManager.acceptCall(sessionId)
    }

    fun endCall() {
        voicePromptManager.speakCallStatus(getApplication<Application>().getString(R.string.call_voice_ending))
        callManager.endCall()
        stopCallTimer()
        _navigationEvent.value = CallNavigationEvent.NavigateBack
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        callManager.toggleMute(newMuted)
        _uiState.update { it.copy(isMuted = newMuted) }
        if (newMuted) {
            voicePromptManager.speak(getApplication<Application>().getString(R.string.call_voice_microphone_off))
        } else {
            voicePromptManager.speak(getApplication<Application>().getString(R.string.call_voice_microphone_on))
        }
    }

    fun toggleVideo() {
        val newVideoOff = !_uiState.value.isVideoOff
        callManager.toggleVideo(!newVideoOff)
        _uiState.update { it.copy(isVideoOff = newVideoOff) }
    }

    fun switchCamera() {
        callManager.switchCamera()
    }

    fun toggleSpeaker() {
        _uiState.update { it.copy(isSpeakerOn = !it.isSpeakerOn) }
    }

    fun getEglBase() = callManager.getEglBase()

    private fun handleCallStateChange(state: CallState) {
        when (state) {
            is CallState.Connecting -> {
                _uiState.update { it.copy(status = CallStatusUi.CONNECTING) }
            }
            is CallState.Ringing -> {
                _uiState.update { it.copy(status = CallStatusUi.RINGING) }
            }
            is CallState.Incoming -> {
                _uiState.update { it.copy(status = CallStatusUi.INCOMING) }
            }
            is CallState.Connected -> {
                _uiState.update { it.copy(status = CallStatusUi.CONNECTED) }
                startCallTimer()
                voicePromptManager.speakCallStatus(getApplication<Application>().getString(R.string.call_voice_connected))
            }
            is CallState.Ended -> {
                _uiState.update { it.copy(status = CallStatusUi.ENDED) }
                stopCallTimer()
                voicePromptManager.speakCallStatus(getApplication<Application>().getString(R.string.call_voice_ended))
                _navigationEvent.value = CallNavigationEvent.NavigateBack
            }
            is CallState.Error -> {
                _uiState.update {
                    it.copy(
                        status = CallStatusUi.ERROR,
                        errorMessage = state.message
                    )
                }
                voicePromptManager.speakCallStatus(getApplication<Application>().getString(R.string.call_voice_error, state.message))
                stopCallTimer()
                viewModelScope.launch {
                    delay(3000)
                    _navigationEvent.value = CallNavigationEvent.NavigateBack
                }
            }
            CallState.Idle -> {
                // No-op
            }
        }
    }

    private fun startCallTimer() {
        stopCallTimer()
        callTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                callDurationSeconds++
                val minutes = callDurationSeconds / 60
                val seconds = callDurationSeconds % 60
                _uiState.update {
                    it.copy(
                        callDuration = String.format("%02d:%02d", minutes, seconds)
                    )
                }
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
        callDurationSeconds = 0
    }

    private suspend fun getContactName(contactId: String): String {
        val context = getApplication<Application>()
        return try {
            when {
                contactId.contains("mom", ignoreCase = true) ->
                    context.getString(R.string.contact_mom_label)
                contactId.contains("dad", ignoreCase = true) ->
                    context.getString(R.string.contact_dad_label)
                else ->
                    context.getString(R.string.contact_guardian_role_label)
            }
        } catch (e: Exception) {
            context.getString(R.string.contact_guardian_role_label)
        }
    }

    fun consumeNavigationEvent() {
        _navigationEvent.value = null
    }

    override fun onCleared() {
        jobs.forEach { it.cancel() }
        stopCallTimer()
        voicePromptManager.shutdown()
        super.onCleared()
    }
}

/**
 * UI state for the call screen.
 */
data class CallUiState(
    val contactName: String = "",
    val contactId: String = "",
    val hasVideo: Boolean = true,
    val isAudioOnly: Boolean = false,
    val status: CallStatusUi = CallStatusUi.CONNECTING,
    val isMuted: Boolean = false,
    val isVideoOff: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val callDuration: String = "00:00",
    val errorMessage: String? = null,
    val remoteVideoTrack: VideoTrack? = null
)

/**
 * Simplified call status for UI consumption.
 */
enum class CallStatusUi {
    CONNECTING,
    RINGING,
    INCOMING,
    CONNECTED,
    ENDED,
    ERROR
}

/**
 * Navigation events from the call screen.
 */
sealed class CallNavigationEvent {
    data object NavigateBack : CallNavigationEvent()
}
