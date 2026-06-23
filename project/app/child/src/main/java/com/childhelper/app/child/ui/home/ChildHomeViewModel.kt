package com.childhelper.app.child.ui.home

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.app.child.R
import com.childhelper.app.child.detection.CryDetector
import com.childhelper.app.child.detection.MotionDetector
import com.childhelper.app.child.service.MonitoringCoordinator
import com.childhelper.app.child.service.MonitoringService
import com.childhelper.app.child.service.OemBatteryManager
import com.childhelper.app.child.ui.bedtime.VoicePromptManager
import com.childhelper.core.common.model.Contact
import com.childhelper.core.common.model.ContactRole
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.MonitorMode
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    private val monitoringCoordinator: MonitoringCoordinator,
    private val pairingApi: PairingApi
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

        // Observe the single source of truth for monitoring state
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
            val parentDeviceId = securePreferences.getString("paired_parent_device_id")
            val isPaired = securePreferences.getBoolean("is_paired", false)
            if (isPaired && parentDeviceId != null) {
                // Try fetching parent info from server (phone, display name)
                try {
                    val info = pairingApi.getParentInfo(parentDeviceId)
                    val serverName = info["displayName"]?.jsonPrimitive?.contentOrNull
                    val serverPhone = info["phoneNumber"]?.jsonPrimitive?.contentOrNull
                    if (!serverName.isNullOrBlank()) securePreferences.putString("parent_display_name", serverName)
                    if (!serverPhone.isNullOrBlank()) securePreferences.putString("parent_phone_number", serverPhone)
                } catch (_: Exception) { /* server may not have parent info set yet */ }

                val parentName = securePreferences.getString("parent_display_name")
                    ?: getApplication<Application>().getString(R.string.contact_parent_label)
                val parentPhone = securePreferences.getString("parent_phone_number")

                val defaultContacts = listOf(
                    Contact(
                        id = parentDeviceId,
                        name = parentName,
                        role = ContactRole.GUARDIAN,
                        isPrimary = true,
                        phoneNumber = parentPhone
                    )
                )
                _uiState.update { it.copy(contacts = defaultContacts) }
            } else {
                _uiState.update { it.copy(contacts = emptyList()) }
            }
        }
    }

    fun onContactClick(contact: Contact, hasVideo: Boolean = true) {
        if (_navigationEvent.value != null) return
        val app = getApplication<Application>()
        val displayName = contact.name
        _navigationEvent.value = HomeNavigationEvent.NavigateToCall(contact.id, hasVideo = hasVideo, contactName = displayName)
    }

    fun onAudioCallClick(contact: Contact) {
        if (_navigationEvent.value != null) return
        val phone = contact.phoneNumber
        if (!phone.isNullOrBlank()) {
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$phone")
            }
            getApplication<Application>().startActivity(intent)
        } else {
            onContactClick(contact, hasVideo = false)
        }
    }

    fun onSosClick() {
        _navigationEvent.value = HomeNavigationEvent.NavigateToSos
    }

    fun onBedtimeModeClick() {
        speakText(getApplication<Application>().getString(R.string.bedtime_mode_voice))
        _navigationEvent.value = HomeNavigationEvent.NavigateToBedtime
    }

    fun onPairingClick() {
        _navigationEvent.value = HomeNavigationEvent.NavigateToPairing
    }

    fun startMonitoring(config: DetectionConfig) {
        val app = getApplication<Application>()
        val intent = Intent(app, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
            putExtra(MonitoringService.EXTRA_CONFIG, MonitoringService.serializeConfig(config))
        }
        ContextCompat.startForegroundService(app, intent)
        speakText(app.getString(R.string.monitoring_started_voice))
    }

    fun autoStartIfNeeded(config: DetectionConfig) {
        viewModelScope.launch {
            val shouldAutoStart = securePreferences.getBoolean("monitoring_auto_start", false)
            if (shouldAutoStart && !monitoringCoordinator.isMonitoring.value) {
                securePreferences.putBoolean("monitoring_auto_start", false)
                startMonitoring(config)
            }
        }
    }

    fun stopMonitoring() {
        val app = getApplication<Application>()
        val intent = Intent(app, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP_MONITORING
        }
        ContextCompat.startForegroundService(app, intent)
        speakText(app.getString(R.string.monitoring_stopped_voice))
    }

    fun speakWelcomeMessage() {
        speakText(getApplication<Application>().getString(R.string.home_welcome_message))
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
    data class NavigateToCall(val contactId: String, val hasVideo: Boolean, val contactName: String) : HomeNavigationEvent()
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
