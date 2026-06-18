package com.childhelper.app.child.ui.sos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.app.child.ui.bedtime.VoicePromptManager
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the SOS screen.
 * Handles SOS activation, countdown display, and guardian communication status.
 */
@HiltViewModel
class SosViewModel @Inject constructor(
    application: Application,
    private val sosManager: SosManager,
    private val securePreferences: SecurePreferences,
    private val voicePromptManager: VoicePromptManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SosUiState())
    val uiState: StateFlow<SosUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<SosNavigationEvent?>(null)
    val navigationEvent: StateFlow<SosNavigationEvent?> = _navigationEvent.asStateFlow()

    private val jobs = mutableListOf<Job>()

    init {
        voicePromptManager.initialize {
            speakSosPrompt()
        }

        jobs += viewModelScope.launch {
            sosManager.sosState.collect { state ->
                _uiState.update {
                    it.copy(
                        sosState = state,
                        isActive = state is SosState.Active,
                        isError = state is SosState.Error,
                        errorMessage = (state as? SosState.Error)?.message
                    )
                }
            }
        }
    }

    private fun speakSosPrompt() {
        voicePromptManager.speak("SOS activated. Help is being notified. Stay calm.")
    }

    fun onSosConfirmed(childDeviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActive = true, countdown = 3) }

            // Countdown with voice prompts
            for (i in 3 downTo 1) {
                _uiState.update { it.copy(countdown = i) }
                voicePromptManager.speak(i.toString())
                kotlinx.coroutines.delay(1000)
            }

            _uiState.update { it.copy(countdown = 0, isNotifying = true) }
            voicePromptManager.speak("Notifying guardians now.")

            sosManager.activateSos(childDeviceId)

            _uiState.update { it.copy(isNotifying = false, notified = true) }
            voicePromptManager.speak("Guardians have been notified. Help is on the way.")
        }
    }

    fun onCancelSos() {
        sosManager.cancelSos()
        voicePromptManager.speak("SOS cancelled.")
        _navigationEvent.value = SosNavigationEvent.NavigateBack
    }

    fun onNavigateHome() {
        _navigationEvent.value = SosNavigationEvent.NavigateHome
    }

    fun consumeNavigationEvent() {
        _navigationEvent.value = null
    }

    override fun onCleared() {
        jobs.forEach { it.cancel() }
        voicePromptManager.shutdown()
        super.onCleared()
    }
}

/**
 * UI state for the SOS screen.
 */
data class SosUiState(
    val sosState: SosState = SosState.Idle,
    val isActive: Boolean = false,
    val isNotifying: Boolean = false,
    val notified: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val countdown: Int = 0
)

/**
 * Navigation events from the SOS screen.
 */
sealed class SosNavigationEvent {
    data object NavigateBack : SosNavigationEvent()
    data object NavigateHome : SosNavigationEvent()
}
