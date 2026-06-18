package com.childhelper.app.parent.ui.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.childhelper.app.parent.R
import com.childhelper.app.parent.ui.dashboard.AlertFeed
import com.childhelper.app.parent.ui.dashboard.alertTypeColor
import com.childhelper.app.parent.ui.dashboard.alertTypeLabel
import com.childhelper.core.common.model.RetentionPeriod


/**
 * Alert History Screen — full history view with filtering and management.
 *
 * Features:
 * - Filter alerts by type (All, Cry, Motion, SOS, Device, Call)
 * - View retention policy status
 * - Export history as text (metadata only, privacy-safe)
 * - Delete individual alerts or clear all history
 * - Grouped by date with scrolling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: AlertHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val alertHistoryClearedMessage = stringResource(R.string.alert_history_cleared)

    // Show data deleted confirmation
    LaunchedEffect(uiState.dataDeleted) {
        if (uiState.dataDeleted) {
            snackbarHostState.showSnackbar(alertHistoryClearedMessage)
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

    // Export dialog
    if (uiState.showExportDialog) {
        ExportDialog(
            exportText = viewModel.exportHistory(),
            onDismiss = { viewModel.dismissExportDialog() }
        )
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog) {
        DeleteAllConfirmationDialog(
            alertCount = uiState.totalCount,
            onConfirm = { viewModel.deleteAllHistory() },
            onDismiss = { viewModel.dismissDeleteDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.alert_history_title),
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
                actions = {
                    // Filter button with dropdown
                    Box {
                        IconButton(onClick = { filterMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.filter_alerts_description)
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false }
                        ) {
                            AlertFilterType.values().forEach { filterType ->
                                val label = when (filterType) {
                                    AlertFilterType.ALL -> stringResource(R.string.filter_all_alerts)
                                    AlertFilterType.CRY -> stringResource(R.string.filter_cry_detection)
                                    AlertFilterType.MOTION -> stringResource(R.string.filter_motion_detection)
                                    AlertFilterType.SOS -> stringResource(R.string.filter_sos_alerts)
                                    AlertFilterType.DEVICE -> stringResource(R.string.filter_device_status)
                                    AlertFilterType.CALL -> stringResource(R.string.filter_call_events)
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            fontWeight = if (uiState.currentFilter == filterType)
                                                FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        viewModel.setFilter(filterType)
                                        filterMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Export button
                    IconButton(onClick = { viewModel.showExportDialog() }) {
                        Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.export_history_description)
                        )
                    }

                    // Delete all button
                    IconButton(onClick = { viewModel.showDeleteDialog() }) {
                        Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = stringResource(R.string.delete_all_history_description),
                            tint = MaterialTheme.colorScheme.error
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
                .padding(paddingValues)
        ) {
            // Filter chips bar
            FilterChipsBar(
                currentFilter = uiState.currentFilter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            // Retention policy indicator
            RetentionIndicator(
                retentionPeriod = uiState.retentionPeriod,
                totalCount = uiState.totalCount,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Alert list
            if (uiState.filteredAlerts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_alerts_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.currentFilter != AlertFilterType.ALL) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.no_alerts_filter_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                AlertFeed(
                    alerts = uiState.filteredAlerts,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Horizontal filter chips bar.
 */
@Composable
private fun FilterChipsBar(
    currentFilter: AlertFilterType,
    onFilterSelected: (AlertFilterType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AlertFilterType.values().forEach { filterType ->
            val label = when (filterType) {
                AlertFilterType.ALL -> stringResource(R.string.filter_all_short)
                AlertFilterType.CRY -> stringResource(R.string.filter_cry_short)
                AlertFilterType.MOTION -> stringResource(R.string.filter_motion_short)
                AlertFilterType.SOS -> stringResource(R.string.filter_sos_short)
                AlertFilterType.DEVICE -> stringResource(R.string.filter_device_short)
                AlertFilterType.CALL -> stringResource(R.string.filter_call_short)
            }
            val selected = currentFilter == filterType
            val containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
            val contentColor = if (selected)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant

            TextButton(
                onClick = { onFilterSelected(filterType) },
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(containerColor)
                    .padding(horizontal = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Retention period indicator bar.
 */
@Composable
private fun RetentionIndicator(
    retentionPeriod: RetentionPeriod,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    val retentionLabel = when (retentionPeriod) {
        RetentionPeriod.OFF -> stringResource(R.string.retention_all)
        RetentionPeriod.TWENTY_FOUR_HOURS -> stringResource(R.string.retention_24_hours)
        RetentionPeriod.SEVEN_DAYS -> stringResource(R.string.retention_7_days)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = retentionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.alert_count_format, totalCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Export dialog showing the exportable text.
 */
@Composable
private fun ExportDialog(
    exportText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.FileDownload,
                contentDescription = null
            )
        },
        title = {
            Text(text = stringResource(R.string.export_dialog_title))
        },
        text = {
            if (exportText.isBlank()) {
                Text(
                    text = stringResource(R.string.export_no_alerts),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.export_privacy_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = exportText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
                Button(onClick = onDismiss) {
                Text(stringResource(R.string.done_label))
            }
        }
    )
}

/**
 * Delete all confirmation dialog.
 */
@Composable
private fun DeleteAllConfirmationDialog(
    alertCount: Int,
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
            Text(text = stringResource(R.string.clear_all_history_title))
        },
        text = {
            Text(
                text = stringResource(R.string.clear_all_history_message, alertCount),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.delete_all_label))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_label))
            }
        }
    )
}
