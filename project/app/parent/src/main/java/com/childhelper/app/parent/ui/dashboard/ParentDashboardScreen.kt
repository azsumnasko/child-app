package com.childhelper.app.parent.ui.dashboard

import com.childhelper.app.parent.db.AlertEntity
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.childhelper.app.parent.R
import androidx.navigation.NavController

/**
 * Parent Dashboard Screen — main entry point for the parent app.
 *
 * Features:
 * - Responsive layout (adapts to phones and tablets)
 * - Device status card with online/offline, battery, network, mode
 * - Recent alert feed (metadata only, no media)
 * - Quick actions: Live View, Alert History, Settings
 * - Pull-to-refresh
 * - Accessibility support with semantic descriptions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboardScreen(
    navController: NavController,
    viewModel: ParentDashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    // Handle navigation events
    val navEvent by viewModel.navigationEvent.collectAsState()
    LaunchedEffect(navEvent) {
        when (navEvent) {
            is DashboardNavigationEvent.NavigateToLiveView -> {
                navController.navigate("live_view")
                viewModel.consumeNavigationEvent()
            }
            is DashboardNavigationEvent.NavigateToAlertHistory -> {
                navController.navigate("alert_history")
                viewModel.consumeNavigationEvent()
            }
            is DashboardNavigationEvent.NavigateToSettings -> {
                navController.navigate("settings")
                viewModel.consumeNavigationEvent()
            }
            is DashboardNavigationEvent.NavigateToPairing -> {
                navController.navigate("pairing")
                viewModel.consumeNavigationEvent()
            }
            null -> { /* no-op */ }
        }
    }

    // Refresh on first composition and each return to this screen
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // Show error snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.dashboard_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    OutlinedButton(
                        onClick = { viewModel.onSettingsClick() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.settings_label))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isTablet) {
                // Tablet: Two-column layout
                TabletDashboardLayout(
                    uiState = uiState,
                    onLiveViewClick = { viewModel.onLiveViewClick() },
                    onAlertHistoryClick = { viewModel.onAlertHistoryClick() },
                    onSettingsClick = { viewModel.onSettingsClick() },
                    onPairDeviceClick = { viewModel.onPairNewDeviceClick() }
                )
            } else {
                // Phone: Single-column scrollable layout
                PhoneDashboardLayout(
                    uiState = uiState,
                    onLiveViewClick = { viewModel.onLiveViewClick() },
                    onAlertHistoryClick = { viewModel.onAlertHistoryClick() },
                    onSettingsClick = { viewModel.onSettingsClick() },
                    onPairDeviceClick = { viewModel.onPairNewDeviceClick() }
                )
            }
        }
    }
}

/**
 * Phone layout — single column, vertically scrollable.
 */
@Composable
private fun PhoneDashboardLayout(
    uiState: DashboardUiState,
    onLiveViewClick: () -> Unit,
    onAlertHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPairDeviceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Device Status Card
        DeviceStatusCard(
            deviceStatus = uiState.deviceStatus,
            childName = uiState.childName
        )

        // Quick Actions
        QuickActionsRow(
            onLiveViewClick = onLiveViewClick,
            onAlertHistoryClick = onAlertHistoryClick,
            onSettingsClick = onSettingsClick,
            onPairDeviceClick = onPairDeviceClick
        )

        // Recent Alerts Section
        AlertsSection(
            alerts = uiState.recentAlerts,
            onViewAllClick = onAlertHistoryClick
        )
    }
}

/**
 * Tablet layout — two columns, side by side.
 */
@Composable
private fun TabletDashboardLayout(
    uiState: DashboardUiState,
    onLiveViewClick: () -> Unit,
    onAlertHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPairDeviceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Left column: Status + Actions
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DeviceStatusCard(
                deviceStatus = uiState.deviceStatus,
                childName = uiState.childName
            )

            QuickActionsColumn(
                onLiveViewClick = onLiveViewClick,
                onAlertHistoryClick = onAlertHistoryClick,
                onSettingsClick = onSettingsClick,
                onPairDeviceClick = onPairDeviceClick
            )
        }

        // Right column: Alerts
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.alert_history_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButtonAction(
                            label = stringResource(R.string.view_all_label),
                            onClick = onAlertHistoryClick
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AlertFeed(
                        alerts = uiState.recentAlerts,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)
                    )
                }
            }
        }
    }
}

/**
 * Quick action buttons row for phone layout.
 */
@Composable
private fun QuickActionsRow(
    onLiveViewClick: () -> Unit,
    onAlertHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPairDeviceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButton(
            icon = Icons.Default.Videocam,
            label = stringResource(R.string.live_view_label),
            onClick = onLiveViewClick,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            icon = Icons.Default.History,
            label = stringResource(R.string.history_label),
            onClick = onAlertHistoryClick,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            icon = Icons.Default.Settings,
            label = stringResource(R.string.settings_label),
            onClick = onSettingsClick,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            icon = Icons.Default.Link,
            label = stringResource(R.string.pair_button_label),
            onClick = onPairDeviceClick,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Quick action buttons column for tablet layout.
 */
@Composable
private fun QuickActionsColumn(
    onLiveViewClick: () -> Unit,
    onAlertHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPairDeviceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButtonLarge(
            icon = Icons.Default.Videocam,
            label = stringResource(R.string.live_view_label),
            description = stringResource(R.string.live_view_description),
            onClick = onLiveViewClick
        )
        QuickActionButtonLarge(
            icon = Icons.Default.History,
            label = stringResource(R.string.alert_history_label),
            description = stringResource(R.string.alert_history_description),
            onClick = onAlertHistoryClick
        )
        QuickActionButtonLarge(
            icon = Icons.Default.Settings,
            label = stringResource(R.string.settings_label),
            description = stringResource(R.string.settings_description),
            onClick = onSettingsClick
        )
        QuickActionButtonLarge(
            icon = Icons.Default.Link,
            label = stringResource(R.string.pairing_pair_new_device),
            description = stringResource(R.string.pairing_device_description),
            onClick = onPairDeviceClick
        )
    }
}

/**
 * Compact quick action button.
 */
@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/**
 * Large quick action button for tablet layout.
 */
@Composable
private fun QuickActionButtonLarge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
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

/**
 * Alerts section with "View All" link.
 */
@Composable
private fun AlertsSection(
    alerts: List<AlertEntity>,
    onViewAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.recent_alerts_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TextButtonAction(
                label = stringResource(R.string.view_all_label),
                onClick = onViewAllClick
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        CompactAlertFeed(alerts = alerts)
    }
}

/**
 * Simple text button action.
 */
@Composable
private fun TextButtonAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Text(label)
    }
}
