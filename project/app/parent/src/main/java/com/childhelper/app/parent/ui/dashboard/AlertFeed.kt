package com.childhelper.app.parent.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.childhelper.app.parent.db.AlertEntity
import com.childhelper.app.parent.ui.theme.AlertCall
import com.childhelper.app.parent.ui.theme.AlertCamera
import com.childhelper.app.parent.ui.theme.AlertCry
import com.childhelper.app.parent.ui.theme.AlertDevice
import com.childhelper.app.parent.ui.theme.AlertMotion
import com.childhelper.app.parent.ui.theme.AlertSos
import com.childhelper.core.common.model.AlertType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Get color for an alert type.
 */
@Composable
fun alertTypeColor(eventType: String): androidx.compose.ui.graphics.Color {
    return try {
        when (AlertType.valueOf(eventType)) {
            AlertType.CRY_DETECTED -> AlertCry
            AlertType.MOTION_DETECTED -> AlertMotion
            AlertType.SOS_ACTIVATED -> AlertSos
            AlertType.CAMERA_OBSTRUCTED -> AlertCamera
            AlertType.DEVICE_OFFLINE -> AlertDevice
            AlertType.LOW_BATTERY -> AlertDevice
            AlertType.CALL_STARTED -> AlertCall
            AlertType.CALL_ENDED -> AlertCall
            AlertType.THERMAL_WARNING -> AlertDevice
            AlertType.DEVICE_OVERHEATING -> AlertDevice
        }
    } catch (_: IllegalArgumentException) {
        MaterialTheme.colorScheme.outline
    }
}

/**
 * Get display label for an alert type.
 */
fun alertTypeLabel(eventType: String): String {
    return try {
        when (AlertType.valueOf(eventType)) {
            AlertType.CRY_DETECTED -> "Cry Detected"
            AlertType.MOTION_DETECTED -> "Motion Detected"
            AlertType.SOS_ACTIVATED -> "SOS Alert"
            AlertType.CAMERA_OBSTRUCTED -> "Camera Obstructed"
            AlertType.DEVICE_OFFLINE -> "Device Offline"
            AlertType.LOW_BATTERY -> "Low Battery"
            AlertType.CALL_STARTED -> "Call Started"
            AlertType.CALL_ENDED -> "Call Ended"
            AlertType.THERMAL_WARNING -> "Thermal Warning"
            AlertType.DEVICE_OVERHEATING -> "Device Overheating"
        }
    } catch (_: IllegalArgumentException) {
        eventType
    }
}

/**
 * Single alert item card.
 * PRIVACY: Shows only metadata — NO media thumbnails, NO audio/video content.
 */
@Composable
fun AlertItem(
    alert: AlertEntity,
    modifier: Modifier = Modifier
) {
    val color = alertTypeColor(alert.eventType)
    val label = alertTypeLabel(alert.eventType)
    val timeFormatted = remember(alert.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(alert.timestamp))
    }
    val confidenceText = alert.confidence?.let {
        " ${(it * 100).toInt()}%"
    } ?: ""

    val description = "$label at $timeFormatted$confidenceText confidence"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored indicator dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Alert info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Confidence badge (if available)
            alert.confidence?.let { conf ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${(conf * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = color
                    )
                }
            }
        }
    }
}

/**
 * Date section header for grouped alerts.
 */
@Composable
fun DateHeader(
    dateLabel: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = dateLabel,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics { heading() }
    )
}

/**
 * Group alerts by date for display.
 */
fun groupAlertsByDate(alerts: List<AlertEntity>): Map<String, List<AlertEntity>> {
    val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    val today = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())
    val yesterdayCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    val yesterday = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(yesterdayCal.time)

    return alerts.groupBy { alert ->
        val dateStr = dateFormat.format(Date(alert.timestamp))
        when (dateStr) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> dateStr
        }
    }
}

/**
 * Scrollable alert feed grouped by date.
 * PRIVACY: Displays only metadata — event type, timestamp, confidence.
 * NO media thumbnails, NO audio/video previews, NO raw data.
 */
@Composable
fun AlertFeed(
    alerts: List<AlertEntity>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp)
) {
    val grouped = remember(alerts) { groupAlertsByDate(alerts) }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        grouped.forEach { (dateLabel, dateAlerts) ->
            item(key = "header_$dateLabel") {
                DateHeader(dateLabel = dateLabel)
            }

            items(
                items = dateAlerts,
                key = { it.id }
            ) { alert ->
                AlertItem(alert = alert)
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/**
 * Compact alert feed for embedding in the dashboard (shows only recent items).
 */
@Composable
fun CompactAlertFeed(
    alerts: List<AlertEntity>,
    modifier: Modifier = Modifier,
    maxItems: Int = 5
) {
    val recentAlerts = remember(alerts) { alerts.take(maxItems) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        recentAlerts.forEach { alert ->
            AlertItem(alert = alert)
        }

        if (alerts.size > maxItems) {
            Text(
                text = "+ ${alerts.size - maxItems} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        if (alerts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No alerts yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
