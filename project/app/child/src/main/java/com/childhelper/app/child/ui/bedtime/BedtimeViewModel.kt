package com.childhelper.app.child.ui.bedtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.childhelper.app.child.service.MonitoringCoordinator
import com.childhelper.app.child.ui.call.CallManager
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.SensitivityLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Bedtime Mode screen.
 *
 * Manages:
 * - Dimmed screen brightness via WindowManager
 * - Calming voice messages on rotation
 * - Auto-answer incoming calls
 * - Cry/motion detection at high sensitivity
 * - Dark theme enforcement
 */
@HiltViewModel
class BedtimeViewModel @Inject constructor(
    application: Application,
    private val voicePromptManager: VoicePromptManager,
    private val monitoringCoordinator: MonitoringCoordinator,
    private val callManager: CallManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BedtimeUiState())
    val uiState: StateFlow<BedtimeUiState> = _uiState.asStateFlow()

    private var bedtimeMessageJob: Job? = null
    private var voiceMessageIndex = 0

    private val jobs = mutableListOf<Job>()

    init {
        voicePromptManager.initialize {
            // TTS is ready — session will be started from the UI with a LifecycleOwner
        }

        // Monitor incoming calls for auto-answer
        jobs += viewModelScope.launch {
            callManager.callState.collect { state ->
                if (state is CallState.Incoming && _uiState.value.autoAnswerEnabled) {
                    // Auto-answer after a short delay to let the child know
                    delay(2000)
                    voicePromptManager.speakCallStatus("Incoming call from ${state.callerName}. Answering automatically.")
                    callManager.acceptCall(state.sessionId)
                }
            }
        }
    }

    /**
     * Start the bedtime session with calming voice, monitoring, and screen dimming.
     *
     * @param lifecycleOwner The LifecycleOwner to bind the camera pipeline to
     */
    fun startBedtimeSession(lifecycleOwner: LifecycleOwner) {
        if (_uiState.value.isActive) return

        _uiState.update { it.copy(isActive = true) }

        // Welcome message
        voicePromptManager.speakBedtimeMessage(
            "Bedtime mode is on. Sleep well. I am watching over you."
        )

        // Start cry and motion detection at high sensitivity via the coordinator
        // (single source of truth for monitoring state)
        val bedtimeConfig = DetectionConfig(
            sensitivity = SensitivityLevel.HIGH,
            cryEnabled = true,
            motionEnabled = true,
            cryThreshold = 0.6f, // More sensitive during bedtime
            motionThreshold = 0.1f
        )

        monitoringCoordinator.startMonitoring(bedtimeConfig, lifecycleOwner)

        // Start periodic calming messages
        startCalmingMessages()
    }

    /**
     * End the bedtime session and restore normal settings.
     */
    fun endBedtimeSession() {
        _uiState.update { it.copy(isActive = false, isExiting = true) }

        bedtimeMessageJob?.cancel()
        voicePromptManager.speak("Good morning. Bedtime mode is off.")

        monitoringCoordinator.stopMonitoring()

        viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(isExiting = false) }
        }
    }

    /**
     * Play an immediate calming voice message.
     */
    fun playCalmingMessage() {
        val message = voicePromptManager.getRandomBedtimeMessage()
        voicePromptManager.speakBedtimeMessage(message)
    }

    /**
     * Toggle auto-answer for incoming calls during bedtime.
     */
    fun toggleAutoAnswer(enabled: Boolean) {
        _uiState.update { it.copy(autoAnswerEnabled = enabled) }
        if (enabled) {
            voicePromptManager.speak("Auto answer is on. Calls will be answered automatically.")
        } else {
            voicePromptManager.speak("Auto answer is off.")
        }
    }

    /**
     * Set screen brightness level (0.0 to 1.0).
     */
    fun setBrightness(brightness: Float) {
        _uiState.update { it.copy(screenBrightness = brightness.coerceIn(0.05f, 0.5f)) }
    }

    /**
     * Start periodic calming voice messages every few minutes.
     */
    private fun startCalmingMessages() {
        bedtimeMessageJob?.cancel()
        bedtimeMessageJob = viewModelScope.launch {
            // Wait a bit after the welcome message
            delay(60000) // First message after 1 minute

            while (_uiState.value.isActive) {
                val message = voicePromptManager.getRandomBedtimeMessage()
                voicePromptManager.speakBedtimeMessage(message)

                // Random interval between 3-7 minutes to feel natural
                val interval = (180000..420000).random().toLong()
                delay(interval)
            }
        }
    }

    override fun onCleared() {
        jobs.forEach { it.cancel() }
        bedtimeMessageJob?.cancel()
        monitoringCoordinator.stopMonitoring()
        super.onCleared()
    }
}

/**
 * UI state for the bedtime mode screen.
 */
data class BedtimeUiState(
    val isActive: Boolean = false,
    val isExiting: Boolean = false,
    val screenBrightness: Float = 0.1f, // 10% brightness default
    val autoAnswerEnabled: Boolean = true,
    val isMonitoring: Boolean = false,
    val nextMessageInMinutes: Int = 3,
    val totalSleepTimeMinutes: Int = 0
)

/**
 * Call state sealed class for auto-answer monitoring.
 * Mirrors the CallManager state but simplified for the ViewModel.
 */
sealed class CallState {
    data object Idle : CallState()
    data class Incoming(val sessionId: String, val callerName: String) : CallState()
    data class Connected(val sessionId: String) : CallState()
    data object Ended : CallState()
}
