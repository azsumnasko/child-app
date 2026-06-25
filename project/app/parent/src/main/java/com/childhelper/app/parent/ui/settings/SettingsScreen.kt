package com.childhelper.app.parent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.childhelper.app.parent.R
import com.childhelper.core.common.model.RetentionPeriod
import com.childhelper.core.common.model.SensitivityLevel

/**
 * Settings Screen — parent-facing configuration.
 *
 * Features:
 * - Sensitivity sliders (low / normal / high)
 * - Alert history retention toggle (off / 24h / 7d)
 * - SOS escalation order
 * - Data deletion flow with confirmation
 * - Push notification toggle
 * - Location sharing toggle
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Recreate activity after language change
    val context = LocalContext.current
    val languageChanged by viewModel.languageChanged.collectAsState()
    LaunchedEffect(languageChanged) {
        if (languageChanged) {
            val activity = context as? android.app.Activity
            activity?.recreate()
            viewModel.onLanguageChangedHandled()
        }
    }

    // Show data deleted confirmation
    val dataDeletedMessage = stringResource(R.string.settings_data_deleted)
    LaunchedEffect(uiState.dataDeleted) {
        if (uiState.dataDeleted) {
            snackbarHostState.showSnackbar(dataDeletedMessage)
            viewModel.resetDataDeletedFlag()
        }
    }

    // Show error
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(uiState.profileSaveState) {
        when (uiState.profileSaveState) {
            ProfileSaveState.Saved -> {
                snackbarHostState.showSnackbar(context.getString(R.string.settings_profile_saved))
                viewModel.resetProfileSaveState()
            }
            ProfileSaveState.Error -> {
                snackbarHostState.showSnackbar(context.getString(R.string.settings_profile_error))
                viewModel.resetProfileSaveState()
            }
            else -> {}
        }
    }

    // Delete confirmation dialog
    if (uiState.showDeleteConfirmation) {
        DataDeletionConfirmationDialog(
            onConfirm = { viewModel.confirmDataDeletion() },
            onDismiss = { viewModel.cancelDataDeletion() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Parent Profile Section (Mom + Dad phone numbers for child PSTN fallback)
            SettingsSection(
                icon = Icons.Default.Person,
                title = stringResource(R.string.settings_section_profile)
            ) {
                if (!uiState.isPaired) {
                    Text(
                        text = stringResource(R.string.settings_profile_pair_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = uiState.momName,
                        onValueChange = { viewModel.setMomName(it) },
                        label = { Text(stringResource(R.string.settings_profile_mom_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.momPhone,
                        onValueChange = { viewModel.setMomPhone(it) },
                        label = { Text(stringResource(R.string.settings_profile_mom_phone)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.dadName,
                        onValueChange = { viewModel.setDadName(it) },
                        label = { Text(stringResource(R.string.settings_profile_dad_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.dadPhone,
                        onValueChange = { viewModel.setDadPhone(it) },
                        label = { Text(stringResource(R.string.settings_profile_dad_phone)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.saveParentProfile() },
                        enabled = uiState.profileSaveState != ProfileSaveState.Saving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_profile_save))
                    }
                }
            }

            // Detection Settings Section
            SettingsSection(
                icon = Icons.Default.Sensors,
                title = stringResource(R.string.settings_section_detection)
            ) {
                // Sensitivity slider
                SensitivitySelector(
                    currentSensitivity = uiState.sensitivity,
                    onSensitivityChange = { viewModel.setSensitivity(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Cry detection toggle
                SettingsToggleItem(
                    label = stringResource(R.string.settings_cry_detection),
                    description = stringResource(R.string.settings_cry_detection_desc),
                    checked = uiState.cryDetectionEnabled,
                    onCheckedChange = { viewModel.setCryDetectionEnabled(it) }
                )

                // Motion detection toggle
                SettingsToggleItem(
                    label = stringResource(R.string.settings_motion_detection),
                    description = stringResource(R.string.settings_motion_detection_desc),
                    checked = uiState.motionDetectionEnabled,
                    onCheckedChange = { viewModel.setMotionDetectionEnabled(it) }
                )
            }

            // Alert History Section
            SettingsSection(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.settings_section_alert_history)
            ) {
                RetentionSelector(
                    currentRetention = uiState.alertHistoryRetention,
                    onRetentionChange = { viewModel.setAlertHistoryRetention(it) }
                )
            }

            // SOS Escalation Section
            SettingsSection(
                icon = Icons.Default.Security,
                title = stringResource(R.string.settings_section_sos)
            ) {
                SosEscalationOrder(
                    escalationOrder = uiState.sosEscalationOrder,
                    onOrderChange = { viewModel.setSosEscalationOrder(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggleItem(
                    label = stringResource(R.string.settings_bedtime_auto_answer),
                    description = stringResource(R.string.settings_bedtime_auto_answer_desc),
                    checked = uiState.bedtimeAutoAnswer,
                    onCheckedChange = { viewModel.setBedtimeAutoAnswer(it) }
                )
            }

            // General Section
            SettingsSection(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.settings_section_general)
            ) {
                LanguageSelector(
                    selectedLanguage = uiState.selectedLanguage,
                    onLanguageChange = { viewModel.setLanguage(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggleItem(
                    label = stringResource(R.string.settings_push_notifications),
                    description = stringResource(R.string.settings_push_notifications_desc),
                    checked = uiState.pushNotificationsEnabled,
                    onCheckedChange = { viewModel.setPushNotificationsEnabled(it) }
                )

                SettingsToggleItem(
                    label = stringResource(R.string.settings_location_sharing),
                    description = stringResource(R.string.settings_location_sharing_desc),
                    checked = uiState.locationSharingEnabled,
                    onCheckedChange = { viewModel.setLocationSharingEnabled(it) }
                )
            }

            // Danger Zone — Data Deletion
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_danger_zone),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_danger_zone_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.requestDataDeletion() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_delete_all_data))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Section container with icon and title.
 */
@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { heading() }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Sensitivity selector with labeled positions.
 */
@Composable
private fun SensitivitySelector(
    currentSensitivity: SensitivityLevel,
    onSensitivityChange: (SensitivityLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_sensitivity),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_sensitivity_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SensitivityLevel.values().forEach { level ->
                val (label, description) = when (level) {
                    SensitivityLevel.LOW -> stringResource(R.string.settings_sensitivity_low) to stringResource(R.string.settings_sensitivity_low_desc)
                    SensitivityLevel.NORMAL -> stringResource(R.string.settings_sensitivity_normal) to stringResource(R.string.settings_sensitivity_normal_desc)
                    SensitivityLevel.HIGH -> stringResource(R.string.settings_sensitivity_high) to stringResource(R.string.settings_sensitivity_high_desc)
                }
                val selected = currentSensitivity == level
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSensitivityChange(level) }
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Retention period selector (radio buttons).
 */
@Composable
private fun RetentionSelector(
    currentRetention: RetentionPeriod,
    onRetentionChange: (RetentionPeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_retention),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_retention_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        RetentionPeriod.values().forEach { period ->
            val (label, description) = when (period) {
                RetentionPeriod.OFF -> stringResource(R.string.settings_retention_keep_all) to stringResource(R.string.settings_retention_keep_all_desc)
                RetentionPeriod.TWENTY_FOUR_HOURS -> stringResource(R.string.settings_retention_24h) to stringResource(R.string.settings_retention_24h_desc)
                RetentionPeriod.SEVEN_DAYS -> stringResource(R.string.settings_retention_7d) to stringResource(R.string.settings_retention_7d_desc)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
            ) {
                RadioButton(
                    selected = currentRetention == period,
                    onClick = { onRetentionChange(period) }
                )
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * SOS escalation order display.
 */
@Composable
private fun SosEscalationOrder(
    escalationOrder: List<String>,
    onOrderChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_sos_order),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_sos_order_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (escalationOrder.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_sos_no_contacts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            escalationOrder.forEachIndexed { index, contact ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = contact,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * Toggle setting item with label and description.
 */
@Composable
private fun SettingsToggleItem(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Data deletion confirmation dialog.
 */
@Composable
private fun DataDeletionConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(text = stringResource(R.string.settings_delete_confirm_title))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_delete_confirm_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.settings_delete_item_alerts))
                    Text(stringResource(R.string.settings_delete_item_settings))
                    Text(stringResource(R.string.settings_delete_item_cache))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_delete_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.settings_delete_confirm_button))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_label))
            }
        }
    )
}

@Composable
private fun LanguageSelector(
    selectedLanguage: String?,
    onLanguageChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_language_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        val options = listOf(
            null to stringResource(R.string.settings_language_system),
            "en" to stringResource(R.string.settings_language_english),
            "bg" to stringResource(R.string.settings_language_bulgarian)
        )

        options.forEach { (code, label) ->
            val selected = selectedLanguage == code || (code == null && selectedLanguage == null)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { onLanguageChange(code) }
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
