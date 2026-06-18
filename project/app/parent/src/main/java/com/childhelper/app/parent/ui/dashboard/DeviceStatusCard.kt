package com.childhelper.app.parent.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childhelper.app.parent.ui.theme.BatteryCritical
import com.childhelper.app.parent.ui.theme.BatteryFull
import com.childhelper.app.parent.ui.theme.BatteryGood
import com.childhelper.app.parent.ui.theme.BatteryLow
import com.childhelper.app.parent.ui.theme.BatteryMedium
import com.childhelper.app.parent.ui.theme.StatusIdle
import com.childhelper.app.parent.ui.theme.StatusOffline
import com.childhelper.app.parent.ui.theme.StatusOnline
import com.childhelper.app.parent.ui.theme.StatusWarning
import com.childhelper.core.common.model.DeviceStatus
import com.childhelper.core.common.model.MonitorMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status indicator dot with animated color.
 */
@Composable
fun StatusDot(
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color = StatusIdle,
    contentDescription: String = ""
) {
    val color by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        label = "status_dot"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { this.contentDescription = contentDescription }
    )
}

/**
 * Battery indicator with color-coded levels.
 */
@Composable
fun BatteryIndicator(
    batteryPercent: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val batteryColor = when {
        batteryPercent >= 80 -> BatteryFull
        batteryPercent >= 50 -> BatteryGood
        batteryPercent >= 30 -> BatteryMedium
        batteryPercent >= 15 -> BatteryLow
        else -> BatteryCritical
    }

    val chargingDescription = if (isCharging) ", charging" else ""
    val description = "Battery $batteryPercent percent$chargingDescription"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = description }
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(batteryColor.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(batteryPercent / 100f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(batteryColor)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$batteryPercent%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = batteryColor
        )
        if (isCharging) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "\u26A1", // Lightning bolt emoji
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Network type indicator icon with label.
 */
@Composable
fun NetworkIndicator(
    networkType: String,
    modifier: Modifier = Modifier
) {
    val (icon, label) = when (networkType.lowercase()) {
        "wifi" -> "Wi-Fi" to "Wi-Fi"
        "cellular" -> "Cellular" to "Cellular"
        else -> "No Network" to "None"
    }

    val iconTint = when (networkType.lowercase()) {
        "wifi" -> MaterialTheme.colorScheme.primary
        "cellular" -> MaterialTheme.colorScheme.secondary
        else -> StatusOffline
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = "Network: $label" }
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(iconTint)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = icon,
            style = MaterialTheme.typography.labelSmall,
            color = iconTint
        )
    }
}

/**
 * Monitor mode badge with color coding.
 */
@Composable
fun MonitorModeBadge(mode: MonitorMode) {
    val (label, color) = when (mode) {
        MonitorMode.IDLE -> "Idle" to StatusIdle
        MonitorMode.BEDTIME -> "Bedtime" to MaterialTheme.colorScheme.primary
        MonitorMode.CALLING -> "Calling" to StatusWarning
        MonitorMode.SOS -> "SOS" to StatusOffline
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "Monitor mode: $label" }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/**
 * Card displaying the child device's current status.
 * Responsive layout that adapts between compact and expanded forms.
 */
@Composable
fun DeviceStatusCard(
    deviceStatus: DeviceStatus,
    childName: String = "Child Device",
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    val lastSeenFormatted = SimpleDateFormat(
        "MMM dd, HH:mm",
        Locale.getDefault()
    ).format(Date(deviceStatus.lastSeen))

    val statusDescription = buildString {
        append("$childName is ")
        append(if (deviceStatus.isOnline) "online" else "offline")
        append(", battery at ${deviceStatus.batteryPercent} percent")
        if (deviceStatus.isCharging) append(", charging")
        append(", network ${deviceStatus.networkType}")
        append(", mode ${deviceStatus.monitorMode.name.lowercase()}")
        append(", last seen $lastSeenFormatted")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = statusDescription },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (isTablet) {
            // Expanded tablet layout — horizontal arrangement
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Name + online status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        isActive = deviceStatus.isOnline,
                        activeColor = StatusOnline,
                        inactiveColor = StatusOffline,
                        contentDescription = if (deviceStatus.isOnline) "Online" else "Offline"
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = childName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (deviceStatus.isOnline) "Online" else "Offline \u00B7 $lastSeenFormatted",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (deviceStatus.isOnline) StatusOnline else StatusOffline
                        )
                    }
                }

                // Center: Battery + Network
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BatteryIndicator(
                        batteryPercent = deviceStatus.batteryPercent,
                        isCharging = deviceStatus.isCharging
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    NetworkIndicator(networkType = deviceStatus.networkType)
                }

                // Right: Monitor mode badge
                MonitorModeBadge(mode = deviceStatus.monitorMode)
            }
        } else {
            // Compact phone layout — vertical arrangement
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Top row: status dot + name + mode badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            isActive = deviceStatus.isOnline,
                            activeColor = StatusOnline,
                            inactiveColor = StatusOffline
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = childName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    MonitorModeBadge(mode = deviceStatus.monitorMode)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom row: battery + network + last seen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BatteryIndicator(
                        batteryPercent = deviceStatus.batteryPercent,
                        isCharging = deviceStatus.isCharging
                    )
                    NetworkIndicator(networkType = deviceStatus.networkType)
                    Text(
                        text = if (deviceStatus.isOnline) "Online" else lastSeenFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (deviceStatus.isOnline) StatusOnline else StatusOffline
                    )
                }
            }
        }
    }
}
