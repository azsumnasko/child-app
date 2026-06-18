package com.childhelper.app.child.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.childhelper.app.child.detection.CryDetector
import com.childhelper.app.child.detection.MotionDetector
import com.childhelper.app.child.service.MonitoringCoordinator
import com.childhelper.app.child.service.OemBatteryManager
import com.childhelper.app.child.ui.bedtime.VoicePromptManager
import com.childhelper.core.common.model.Contact
import com.childhelper.core.common.model.ContactRole
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.MonitorMode
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the child home screen.
 *
 * Monitoring state is observed from [MonitoringCoordinator.isMonitoring], which is
 * the **single source of truth**. This ensures the UI always reflects the actual
 * monitoring state even when it is changed by other components (e.g.
 * [MonitoringService], thermal shutdown, or bedtime mode).
 *
 * @param monitoringCoordinator Single source of truth for monitoring state.
 *                              Injected so the UI can observe it; direct
 *                              start/stop calls still go through the detectors
 *                              for backward compatibility.
 */
@HiltViewModel
class ChildHomeViewModel @Inject constructor(
    application: Application,
    private val securePreferences: SecurePreferences,
    private val cryDetector: CryDetector,
    private val motionDetector: MotionDetector,
    private val voicePromptManager: VoicePromptManager,
    private val monitoringCoordinator: MonitoringCoordinator
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChildHomeUiState())
    val uiState: StateFlow<ChildHomeUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<HomeNavigationEvent?>(null)
    val navigationEvent: StateFlow<HomeNavigationEvent?> = _navigationEvent.asStateFlow()

    private val _batteryWhitelistEvent = MutableStateFlow<BatteryWhitelistEvent?>(null)
    val batteryWhitelistEvent: StateFlow<BatteryWhitelistEvent?> = _batteryWhitelistEvent.asStateFlow()

    private val oemBatteryManager = OemBatteryManager(application)

    init {
        loadContacts()
        voicePromptManager.initialize {
            _uiState.update { it.copy(isTtsReady = true) }
            speakWelcomeMessage()
        }
        checkBatteryWhitelist()

        // P0-4 FIX: Observe the single source of truth for monitoring state.
        // This guarantees the UI always reflects the real state even when
        // MonitoringService or thermal throttling changes it.
        viewModelScope.launch {
            monitoringCoordinator.isMonitoring.collect { isActive ->
                _uiState.update { it.copy(isMonitoring = isActive) }
            }
        }
    }

    /**
     * Check whether the app needs to show the battery whitelist dialog.
     * Only shows once (on first launch) unless the user is not yet whitelisted.
     */
    private fun checkBatteryWhitelist() {
        viewModelScope.launch {
            // Skip if already whitelisted
            if (oemBatteryManager.isIgnoringBatteryOptimizations()) {
                return@launch
            }
            // Only show dialog on first launch; user can re-trigger from settings
            if (oemBatteryManager.hasShownDialog()) {
                return@launch
            }
            val status = oemBatteryManager.getWhitelistStatus()
            _batteryWhitelistEvent.value = BatteryWhitelistEvent.ShowDialog(status)
        }
    }

    fun dismissBatteryWhitelistDialog() {
        oemBatteryManager.markDialogShown()
        _batteryWhitelistEvent.value = null
    }

    fun requestBatteryWhitelist() {
        oemBatteryManager.requestIgnoreBatteryOptimizations()
    }

    fun openOemBatterySettings() {
        oemBatteryManager.openOemSettings()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            // In production, load from secure preferences or repository
            // For now, use sample data that would be set up during pairing
            val defaultContacts = listOf(
                Contact(
                    id = "1",
                    name = "Mom",
                    role = ContactRole.MOTHER,
                    isPrimary = true
                ),
                Contact(
                    id = "2",
                    name = "Dad",
                    role = ContactRole.FATHER,
                    isPrimary = false
                )
            )
            _uiState.update { it.copy(contacts = defaultContacts) }
        }
    }

    fun onContactClick(contact: Contact) {
        if (contact.isPrimary) {
            speakText("Calling Mom")
        }
        _navigationEvent.value = HomeNavigationEvent.NavigateToCall(contact.id, hasVideo = true)
    }

    fun onSosClick() {
        _navigationEvent.value = HomeNavigationEvent.NavigateToSos
    }

    fun onBedtimeModeClick() {
        speakText("Bedtime mode")
        _navigationEvent.value = HomeNavigationEvent.NavigateToBedtime
    }

    fun onPairingClick() {
        _navigationEvent.value = HomeNavigationEvent.NavigateToPairing
    }

    fun startMonitoring(config: DetectionConfig, lifecycleOwner: LifecycleOwner) {
        viewModelScope.launch {
            // P0-4 FIX: Delegate to MonitoringCoordinator — the single source of truth.
            // This prevents race conditions where the ViewModel and Service
            // independently start/stop detectors and disagree on state.
            monitoringCoordinator.startMonitoring(config, lifecycleOwner)
            speakText("Monitoring started")
        }
    }

    fun stopMonitoring() {
        viewModelScope.launch {
            monitoringCoordinator.stopMonitoring()
            speakText("Monitoring stopped")
        }
    }

    fun speakWelcomeMessage() {
        speakText("Tap Mom or Dad to call. Hold the SOS button for help.")
    }

    fun speakText(text: String) {
        if (_uiState.value.isTtsReady) {
            voicePromptManager.speak(text)
        }
    }

    fun consumeNavigationEvent() {
        _navigationEvent.value = null
    }

    override fun onCleared() {
        super.onCleared()
        voicePromptManager.shutdown()
        // P0-4 FIX: Stop via the coordinator to keep state consistent.
        monitoringCoordinator.stopMonitoring()
    }
}

/**
 * UI state for the child home screen.
 */
data class ChildHomeUiState(
    val contacts: List<Contact> = emptyList(),
    val isMonitoring: Boolean = false,
    val isTtsReady: Boolean = false,
    val currentMode: MonitorMode = MonitorMode.IDLE,
    val batteryPercent: Int = 100,
    val isOnline: Boolean = true
)

/**
 * Navigation events from the home screen.
 */
sealed class HomeNavigationEvent {
    data class NavigateToCall(val contactId: String, val hasVideo: Boolean) : HomeNavigationEvent()
    data object NavigateToSos : HomeNavigationEvent()
    data object NavigateToBedtime : HomeNavigationEvent()
    data object NavigateToPairing : HomeNavigationEvent()
}

/**
 * Battery whitelist UI events.
 */
sealed class BatteryWhitelistEvent {
    /**
     * Show the battery whitelist dialog with the given status.
     */
    data class ShowDialog(val status: OemBatteryManager.WhitelistStatus) : BatteryWhitelistEvent()
}
