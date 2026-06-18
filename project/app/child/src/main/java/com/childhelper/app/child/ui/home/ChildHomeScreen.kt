package com.childhelper.app.child.ui.home

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.childhelper.app.child.R
import com.childhelper.app.child.service.OemBatteryManager
import com.childhelper.app.child.ui.sos.SosButton
import com.childhelper.app.child.ui.theme.ChildColors
import com.childhelper.core.common.model.Contact
import com.childhelper.core.common.model.ContactRole
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.SensitivityLevel

/**
 * The main home screen of the child-facing app.
 *
 * Features:
 * - Large touch targets (minimum 56dp, contacts are 120dp)
 * - Profile photos for contacts with Mom/Dad labels
 * - SOS button with hold-to-activate
 * - Bedtime mode toggle
 * - Voice prompts via TextToSpeech
 * - TalkBack content descriptions for all interactive elements
 * - Monitoring status indicator
 */
@Composable
fun ChildHomeScreen(
    navController: NavController,
    viewModel: ChildHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val navigationEvent by viewModel.navigationEvent.collectAsState()
    val batteryWhitelistEvent by viewModel.batteryWhitelistEvent.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Handle navigation events
    LaunchedEffect(navigationEvent) {
        when (navigationEvent) {
            is HomeNavigationEvent.NavigateToCall -> {
                val event = navigationEvent as HomeNavigationEvent.NavigateToCall
                navController.navigate("call/${event.contactId}?video=${event.hasVideo}")
                viewModel.consumeNavigationEvent()
            }
            HomeNavigationEvent.NavigateToSos -> {
                navController.navigate("sos")
                viewModel.consumeNavigationEvent()
            }
            HomeNavigationEvent.NavigateToBedtime -> {
                navController.navigate("bedtime")
                viewModel.consumeNavigationEvent()
            }
            HomeNavigationEvent.NavigateToPairing -> {
                navController.navigate("pairing")
                viewModel.consumeNavigationEvent()
            }
            null -> { /* no-op */ }
        }
    }

    // Welcome voice prompt on first load
    LaunchedEffect(Unit) {
        viewModel.speakWelcomeMessage()
    }

    // Battery whitelist dialog (P0-5: OEM battery optimization)
    val whitelistStatus = (batteryWhitelistEvent as? BatteryWhitelistEvent.ShowDialog)?.status
    if (whitelistStatus != null) {
        BatteryWhitelistDialog(
            status = whitelistStatus,
            onDismiss = { viewModel.dismissBatteryWhitelistDialog() },
            onRequestWhitelist = { viewModel.requestBatteryWhitelist() },
            onOpenOemSettings = { viewModel.openOemBatterySettings() }
        )
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                isMonitoring = uiState.isMonitoring,
                isOnline = uiState.isOnline,
                batteryPercent = uiState.batteryPercent,
                onBedtimeClick = { viewModel.onBedtimeModeClick() }
            )
        },
        floatingActionButton = {
            // SOS button as floating action for quick access
            SosButton(
                onSosActivated = { viewModel.onSosClick() },
                modifier = Modifier
                    .size(100.dp)
                    .semantics {
                        contentDescription = "SOS Emergency Button. Hold for 2 seconds to activate emergency alert."
                    }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status card
            StatusCard(
                isMonitoring = uiState.isMonitoring,
                onToggleMonitoring = {
                    if (uiState.isMonitoring) {
                        viewModel.stopMonitoring()
                    } else {
                        viewModel.startMonitoring(
                            DetectionConfig(
                                sensitivity = SensitivityLevel.NORMAL,
                                cryEnabled = true,
                                motionEnabled = true
                            ),
                            lifecycleOwner
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Greeting text
            Text(
                text = stringResource(R.string.home_greeting_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.home_greeting_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Contact buttons grid
            ContactsGrid(
                contacts = uiState.contacts,
                onContactClick = { contact -> viewModel.onContactClick(contact) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Additional actions
            QuickActionsRow(
                onAudioCallMom = {
                    val mom = uiState.contacts.find { it.role == ContactRole.MOTHER }
                    mom?.let { viewModel.onContactClick(it.copy(isPrimary = true)) }
                },
                onAudioCallDad = {
                    val dad = uiState.contacts.find { it.role == ContactRole.FATHER }
                    dad?.let { viewModel.onContactClick(it) }
                },
                onPairWithParent = { viewModel.onPairingClick() }
            )

            Spacer(modifier = Modifier.height(100.dp)) // Space for FAB
        }
    }
}

@Composable
private fun HomeTopBar(
    isMonitoring: Boolean,
    isOnline: Boolean,
    batteryPercent: Int,
    onBedtimeClick: () -> Unit
) {
    val onlineStatusDesc = stringResource(if (isOnline) R.string.status_online else R.string.status_offline)
    val batteryDesc = stringResource(R.string.status_battery_format, batteryPercent)
    val monitoringStatusDesc = stringResource(if (isMonitoring) R.string.status_monitoring_active else R.string.status_monitoring_inactive)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(onlineStatusDesc)
                    append(" ")
                    append(batteryDesc)
                    append(" ")
                    append(monitoringStatusDesc)
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (isMonitoring) ChildColors.Monitoring else ChildColors.Offline
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(if (isMonitoring) R.string.status_watching else R.string.status_idle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Bedtime mode button
        IconButton(
            onClick = onBedtimeClick,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = "Bedtime mode. Tap to enter calming bedtime mode."
                }
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_bedtime),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = ChildColors.BedtimeAccent
            )
        }
    }
}

@Composable
private fun StatusCard(
    isMonitoring: Boolean,
    onToggleMonitoring: () -> Unit
) {
    val cardSemanticsDesc = stringResource(
        if (isMonitoring) R.string.monitoring_content_description_active
        else R.string.monitoring_content_description_inactive
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = cardSemanticsDesc
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMonitoring) {
                ChildColors.Secondary.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(if (isMonitoring) R.string.monitoring_active_title else R.string.monitoring_inactive_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isMonitoring) ChildColors.SecondaryDark
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(if (isMonitoring) R.string.monitoring_active_subtitle else R.string.monitoring_inactive_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onToggleMonitoring,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMonitoring) ChildColors.Secondary else ChildColors.Primary
                )
            ) {
                Text(
                    text = stringResource(if (isMonitoring) R.string.monitoring_stop else R.string.monitoring_start),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ContactsGrid(
    contacts: List<Contact>,
    onContactClick: (Contact) -> Unit
) {
    // Sort contacts: primary first, then by role (Mom, Dad, Guardian)
    val sortedContacts = contacts.sortedWith(
        compareByDescending<Contact> { it.isPrimary }
            .thenBy {
                when (it.role) {
                    ContactRole.MOTHER -> 0
                    ContactRole.FATHER -> 1
                    ContactRole.GUARDIAN -> 2
                }
            }
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        sortedContacts.chunked(2).forEach { rowContacts ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                rowContacts.forEach { contact ->
                    val buttonColor = when (contact.role) {
                        ContactRole.MOTHER -> ChildColors.MomButton
                        ContactRole.FATHER -> ChildColors.DadButton
                        ContactRole.GUARDIAN -> ChildColors.Accent
                    }

                    ContactButton(
                        contact = contact,
                        onClick = { onContactClick(contact) },
                        modifier = Modifier.weight(1f),
                        buttonColor = buttonColor
                    )
                }

                // Fill empty space if odd number of contacts
                if (rowContacts.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Dialog guiding the user to whitelist the app from OEM battery optimization.
 *
 * Without whitelisting, the monitoring service will be killed within 1-2 hours
 * on Xiaomi/OPPO and several hours on Samsung. This dialog explains the steps
 * for the detected OEM brand.
 */
@Composable
private fun BatteryWhitelistDialog(
    status: OemBatteryManager.WhitelistStatus,
    onDismiss: () -> Unit,
    onRequestWhitelist: () -> Unit,
    onOpenOemSettings: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Keep ChildHelper Running",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = "To keep your child safe, ChildHelper must run continuously in the background. " +
                            "Your device (${status.oemBrand.name.lowercase().replaceFirstChar { it.uppercase() }}) " +
                            "may stop the app to save battery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Follow these steps:",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                status.steps.forEachIndexed { index, step ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = step.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = ChildColors.Primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!status.isWhitelisted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Also tap 'Allow Background' below to request system permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!status.isWhitelisted && status.canRequestSystemDialog) {
                    Button(
                        onClick = {
                            onRequestWhitelist()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ChildColors.Primary)
                    ) {
                        Text("Allow Background Running", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        onOpenOemSettings()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("Open ${status.oemBrand.name.lowercase().replaceFirstChar { it.uppercase() }} Settings", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("I'll do this later")
                }
            }
        }
    )
}

@Composable
private fun QuickActionsRow(
    onAudioCallMom: () -> Unit,
    onAudioCallDad: () -> Unit,
    onPairWithParent: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Audio-only call Mom
        Button(
            onClick = onAudioCallMom,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .semantics {
                    contentDescription = "Audio only call to Mom. No video."
                },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ChildColors.MomButtonLight,
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_call_audio),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.audio_call_mom_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Audio-only call Dad
        Button(
            onClick = onAudioCallDad,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .semantics {
                    contentDescription = "Audio only call to Dad. No video."
                },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ChildColors.DadButtonLight,
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_call_audio),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.audio_call_dad_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Pair with Parent
        Button(
            onClick = onPairWithParent,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .semantics {
                    contentDescription = "Pair this device with a parent device."
                },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ChildColors.Secondary,
                contentColor = Color.White
            ),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.pairing_button),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
