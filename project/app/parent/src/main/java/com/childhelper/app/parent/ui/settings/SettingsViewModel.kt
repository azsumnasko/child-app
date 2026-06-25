package com.childhelper.app.parent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.childhelper.app.parent.repository.AlertHistoryRepository
import com.childhelper.core.common.model.RetentionPeriod
import com.childhelper.core.common.model.SensitivityLevel
import com.childhelper.core.common.util.SafeResult
import com.childhelper.core.network.repository.PairingRepository
import com.childhelper.core.security.LocaleManager
import com.childhelper.core.security.SecurePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val sensitivity: SensitivityLevel = SensitivityLevel.NORMAL,
    val cryDetectionEnabled: Boolean = true,
    val motionDetectionEnabled: Boolean = true,
    val alertHistoryRetention: RetentionPeriod = RetentionPeriod.TWENTY_FOUR_HOURS,
    val sosEscalationOrder: List<String> = emptyList(),
    val bedtimeAutoAnswer: Boolean = true,
    val locationSharingEnabled: Boolean = false,
    val pushNotificationsEnabled: Boolean = true,
    val selectedLanguage: String? = null,
    val isLoading: Boolean = true,
    val showDeleteConfirmation: Boolean = false,
    val dataDeleted: Boolean = false,
    val errorMessage: String? = null,
    val isPaired: Boolean = false,
    val momName: String = "",
    val momPhone: String = "",
    val dadName: String = "",
    val dadPhone: String = "",
    val profileSaveState: ProfileSaveState = ProfileSaveState.Idle
)

enum class ProfileSaveState { Idle, Saving, Saved, Error }

/**
 * ViewModel for the settings screen.
 * Manages detection sensitivity, retention policy, SOS settings, and data deletion.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: SecurePreferences,
    private val alertRepository: AlertHistoryRepository,
    private val pairingRepository: PairingRepository
) : ViewModel() {

    private companion object {
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_CRY_ENABLED = "cry_detection_enabled"
        private const val KEY_MOTION_ENABLED = "motion_detection_enabled"
        private const val KEY_BEDTIME_AUTO_ANSWER = "bedtime_auto_answer"
        private const val KEY_LOCATION_SHARING = "location_sharing"
        private const val KEY_PUSH_NOTIFICATIONS = "push_notifications"
        private const val KEY_SOS_ORDER = "sos_escalation_order"
        private const val DEFAULT_SENSITIVITY = "NORMAL"
        private const val DEFAULT_SOS_ORDER = ""
        private const val KEY_PARENT_PHONE = "parent_phone_number"
        private const val KEY_PARENT_NAME = "parent_display_name"
        private const val KEY_DAD_PHONE = "parent_phone_number_dad"
        private const val KEY_DAD_NAME = "parent_display_name_dad"
    }

    private val _sensitivity = MutableStateFlow(SensitivityLevel.NORMAL)
    private val _cryDetectionEnabled = MutableStateFlow(true)
    private val _motionDetectionEnabled = MutableStateFlow(true)
    private val _sosEscalationOrder = MutableStateFlow<List<String>>(emptyList())
    private val _bedtimeAutoAnswer = MutableStateFlow(true)
    private val _locationSharingEnabled = MutableStateFlow(false)
    private val _pushNotificationsEnabled = MutableStateFlow(true)
    private val _selectedLanguage = MutableStateFlow<String?>(null)

    private val _showDeleteConfirmation = MutableStateFlow(false)
    private val _dataDeleted = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _isPaired = MutableStateFlow(false)
    private val _momName = MutableStateFlow("")
    private val _momPhone = MutableStateFlow("")
    private val _dadName = MutableStateFlow("")
    private val _dadPhone = MutableStateFlow("")
    private val _profileSaveState = MutableStateFlow(ProfileSaveState.Idle)

    private val _languageChanged = MutableStateFlow(false)
    val languageChanged: StateFlow<Boolean> = _languageChanged.asStateFlow()

    init {
        viewModelScope.launch {
            val sensitivityStr = preferences.getString(KEY_SENSITIVITY) ?: DEFAULT_SENSITIVITY
            _sensitivity.value = try {
                SensitivityLevel.valueOf(sensitivityStr)
            } catch (_: IllegalArgumentException) {
                SensitivityLevel.NORMAL
            }
            _cryDetectionEnabled.value = preferences.getBoolean(KEY_CRY_ENABLED, true)
            _motionDetectionEnabled.value = preferences.getBoolean(KEY_MOTION_ENABLED, true)
            _bedtimeAutoAnswer.value = preferences.getBoolean(KEY_BEDTIME_AUTO_ANSWER, true)
            _locationSharingEnabled.value = preferences.getBoolean(KEY_LOCATION_SHARING, false)
            _pushNotificationsEnabled.value = preferences.getBoolean(KEY_PUSH_NOTIFICATIONS, true)
            val sosStr = preferences.getString(KEY_SOS_ORDER, DEFAULT_SOS_ORDER) ?: DEFAULT_SOS_ORDER
            _sosEscalationOrder.value = sosStr.split(",").filter { it.isNotBlank() }
            _selectedLanguage.value = preferences.getString(LocaleManager.PREF_KEY_LANGUAGE)
            _isPaired.value = preferences.getBoolean("is_paired", false)
            _momName.value = preferences.getString(KEY_PARENT_NAME) ?: ""
            _momPhone.value = preferences.getString(KEY_PARENT_PHONE) ?: ""
            _dadName.value = preferences.getString(KEY_DAD_NAME) ?: ""
            _dadPhone.value = preferences.getString(KEY_DAD_PHONE) ?: ""
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        _sensitivity,
        _cryDetectionEnabled,
        _motionDetectionEnabled,
        alertRepository.getRetentionPeriod(),
        _sosEscalationOrder,
        _bedtimeAutoAnswer,
        _locationSharingEnabled,
        _pushNotificationsEnabled,
        _selectedLanguage,
        _showDeleteConfirmation,
        _dataDeleted,
        _errorMessage,
        _isPaired,
        _momName,
        _momPhone,
        _dadName,
        _dadPhone,
        _profileSaveState
    ) { values ->
        SettingsUiState(
            sensitivity = values[0] as SensitivityLevel,
            cryDetectionEnabled = values[1] as Boolean,
            motionDetectionEnabled = values[2] as Boolean,
            alertHistoryRetention = values[3] as RetentionPeriod,
            sosEscalationOrder = values[4] as List<String>,
            bedtimeAutoAnswer = values[5] as Boolean,
            locationSharingEnabled = values[6] as Boolean,
            pushNotificationsEnabled = values[7] as Boolean,
            selectedLanguage = values[8] as String?,
            isLoading = false,
            showDeleteConfirmation = values[9] as Boolean,
            dataDeleted = values[10] as Boolean,
            errorMessage = values[11] as String?,
            isPaired = values[12] as Boolean,
            momName = values[13] as String,
            momPhone = values[14] as String,
            dadName = values[15] as String,
            dadPhone = values[16] as String,
            profileSaveState = values[17] as ProfileSaveState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(isLoading = true)
    )

    // --- Sensitivity ---

    fun setSensitivity(level: SensitivityLevel) {
        viewModelScope.launch {
            preferences.putString(KEY_SENSITIVITY, level.name)
            _sensitivity.value = level
        }
    }

    // --- Detection toggles ---

    fun setCryDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.putBoolean(KEY_CRY_ENABLED, enabled)
            _cryDetectionEnabled.value = enabled
        }
    }

    fun setMotionDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.putBoolean(KEY_MOTION_ENABLED, enabled)
            _motionDetectionEnabled.value = enabled
        }
    }

    // --- Parent Profile (Mom + Dad phone numbers for child PSTN fallback) ---

    fun setMomName(value: String) { _momName.value = value }
    fun setMomPhone(value: String) { _momPhone.value = value }
    fun setDadName(value: String) { _dadName.value = value }
    fun setDadPhone(value: String) { _dadPhone.value = value }

    fun saveParentProfile() {
        if (!_isPaired.value) {
            _profileSaveState.value = ProfileSaveState.Error
            return
        }
        _profileSaveState.value = ProfileSaveState.Saving
        viewModelScope.launch {
            preferences.putString(KEY_PARENT_NAME, _momName.value)
            preferences.putString(KEY_PARENT_PHONE, _momPhone.value)
            preferences.putString(KEY_DAD_NAME, _dadName.value)
            preferences.putString(KEY_DAD_PHONE, _dadPhone.value)
            val result = pairingRepository.updateParentInfo(
                momPhoneNumber = _momPhone.value.ifBlank { null },
                momDisplayName = _momName.value.ifBlank { null },
                dadPhoneNumber = _dadPhone.value.ifBlank { null },
                dadDisplayName = _dadName.value.ifBlank { null }
            )
            _profileSaveState.value = when (result) {
                is SafeResult.Success -> ProfileSaveState.Saved
                is SafeResult.Failure -> ProfileSaveState.Error
            }
        }
    }

    fun resetProfileSaveState() { _profileSaveState.value = ProfileSaveState.Idle }

    // --- Retention ---

    fun setAlertHistoryRetention(period: RetentionPeriod) {
        viewModelScope.launch {
            try {
                alertRepository.setRetentionPeriod(period)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update retention: ${e.localizedMessage}"
            }
        }
    }

    // --- SOS Escalation ---

    fun setSosEscalationOrder(order: List<String>) {
        viewModelScope.launch {
            preferences.putString(KEY_SOS_ORDER, order.joinToString(","))
            _sosEscalationOrder.value = order
        }
    }

    // --- Other toggles ---

    fun setBedtimeAutoAnswer(enabled: Boolean) {
        viewModelScope.launch {
            preferences.putBoolean(KEY_BEDTIME_AUTO_ANSWER, enabled)
            _bedtimeAutoAnswer.value = enabled
        }
    }

    fun setLocationSharingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.putBoolean(KEY_LOCATION_SHARING, enabled)
            _locationSharingEnabled.value = enabled
        }
    }

    fun setPushNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.putBoolean(KEY_PUSH_NOTIFICATIONS, enabled)
            _pushNotificationsEnabled.value = enabled
        }
    }

    fun setLanguage(languageCode: String?) {
        viewModelScope.launch {
            if (languageCode != null) {
                preferences.putString(LocaleManager.PREF_KEY_LANGUAGE, languageCode)
            } else {
                preferences.putString(LocaleManager.PREF_KEY_LANGUAGE, "")
            }
            LocaleManager.cacheLanguage(languageCode)
            _selectedLanguage.value = languageCode
            _languageChanged.value = true
        }
    }

    fun onLanguageChangedHandled() {
        _languageChanged.value = false
    }

    // --- Data Deletion ---

    fun requestDataDeletion() {
        _showDeleteConfirmation.value = true
    }

    fun cancelDataDeletion() {
        _showDeleteConfirmation.value = false
    }

    /**
     * Securely delete all local data — alert history, settings, cached data.
     * This is irreversible.
     */
    fun confirmDataDeletion() {
        viewModelScope.launch {
            try {
                // Delete all alert history from database
                val deletedCount = alertRepository.deleteAllHistory()

                // Clear all settings
                preferences.clear()

                _dataDeleted.value = true
                _showDeleteConfirmation.value = false
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete data: ${e.localizedMessage}"
                _showDeleteConfirmation.value = false
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun resetDataDeletedFlag() {
        _dataDeleted.value = false
    }
}
