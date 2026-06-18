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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Notifications
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

    // Show data deleted confirmation
    LaunchedEffect(uiState.dataDeleted) {
        if (uiState.dataDeleted) {
            snackbarHostState.showSnackbar("All data has been securely deleted")
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
                            contentDescription = "Navigate back"
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

            // Detection Settings Section
            SettingsSection(
                icon = Icons.Default.Sensors,
                title = "Detection Settings"
            ) {
                // Sensitivity slider
                SensitivitySelector(
                    currentSensitivity = uiState.sensitivity,
                    onSensitivityChange = { viewModel.setSensitivity(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Cry detection toggle
                SettingsToggleItem(
                    label = "Cry Detection",
                    description = "Detect crying sounds from the child's room",
                    checked = uiState.cryDetectionEnabled,
                    onCheckedChange = { viewModel.setCryDetectionEnabled(it) }
                )

                // Motion detection toggle
                SettingsToggleItem(
                    label = "Motion Detection",
                    description = "Detect motion in the child's room",
                    checked = uiState.motionDetectionEnabled,
                    onCheckedChange = { viewModel.setMotionDetectionEnabled(it) }
                )
            }

            // Alert History Section
            SettingsSection(
                icon = Icons.Default.Notifications,
                title = "Alert History"
            ) {
                RetentionSelector(
                    currentRetention = uiState.alertHistoryRetention,
                    onRetentionChange = { viewModel.setAlertHistoryRetention(it) }
                )
            }

            // SOS Escalation Section
            SettingsSection(
                icon = Icons.Default.Security,
                title = "SOS Escalation"
            ) {
                SosEscalationOrder(
                    escalationOrder = uiState.sosEscalationOrder,
                    onOrderChange = { viewModel.setSosEscalationOrder(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggleItem(
                    label = "Bedtime Auto-Answer",
                    description = "Automatically answer calls during bedtime mode",
                    checked = uiState.bedtimeAutoAnswer,
                    onCheckedChange = { viewModel.setBedtimeAutoAnswer(it) }
                )
            }

            // General Section
            SettingsSection(
                icon = Icons.Default.Settings,
                title = "General"
            ) {
                LanguageSelector(
                    selectedLanguage = uiState.selectedLanguage,
                    onLanguageChange = { viewModel.setLanguage(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggleItem(
                    label = "Push Notifications",
                    description = "Receive alerts as push notifications",
                    checked = uiState.pushNotificationsEnabled,
                    onCheckedChange = { viewModel.setPushNotificationsEnabled(it) }
                )

                SettingsToggleItem(
                    label = "Location Sharing",
                    description = "Share location during SOS events",
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
                            text = "Danger Zone",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Deleting your data will permanently remove all alert history and reset all settings. This action cannot be undone.",
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
                        Text("Delete All Data")
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
            text = "Detection Sensitivity",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Higher sensitivity may increase false positives",
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
                    SensitivityLevel.LOW -> "Low" to "Fewer alerts"
                    SensitivityLevel.NORMAL -> "Normal" to "Balanced"
                    SensitivityLevel.HIGH -> "High" to "More alerts"
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
            text = "Alert History Retention",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "How long to keep alert history on this device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        RetentionPeriod.values().forEach { period ->
            val (label, description) = when (period) {
                RetentionPeriod.OFF -> "Keep All" to "Store alerts indefinitely (max 30 days)"
                RetentionPeriod.TWENTY_FOUR_HOURS -> "24 Hours" to "Keep last 24 hours of alerts"
                RetentionPeriod.SEVEN_DAYS -> "7 Days" to "Keep last 7 days of alerts"
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
            text = "SOS Contact Order",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Who to contact first when SOS is activated",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (escalationOrder.isEmpty()) {
            Text(
                text = "No contacts configured",
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
            Text(text = "Delete All Data?")
        },
        text = {
            Column {
                Text(
                    text = "This will permanently delete:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("\u2022 All alert history")
                    Text("\u2022 All settings and preferences")
                    Text("\u2022 Cached data")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "This action cannot be undone.",
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
                Text("Delete Everything")
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
            text = "App Language",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Restart required for change to take effect",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        val options = listOf(
            null to "System Default",
            "en" to "English",
            "bg" to "Bulgarian"
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
