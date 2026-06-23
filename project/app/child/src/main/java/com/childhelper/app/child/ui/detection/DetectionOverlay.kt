package com.childhelper.app.child.ui.detection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.childhelper.app.child.ui.theme.ChildColors
import com.childhelper.app.child.R
import com.childhelper.core.common.model.Alert
import com.childhelper.core.common.model.AlertType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detection overlay that shows monitoring status and recent alerts.
 * This is a non-intrusive overlay that appears as a floating card
 * when detection events occur.
 *
 * All displayed information is metadata-only:
 * - Event type (cry, motion, camera obstructed)
 * - Confidence score (as a percentage)
 * - Timestamp
 * - Detection status (active/inactive)
 *
 * No raw audio or video is ever displayed.
 */
@Composable
fun DetectionOverlay(
    viewModel: DetectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val recentAlerts by viewModel.recentAlerts.collectAsState()

    // Only show overlay when there are active detections or recent alerts
    val shouldShow = uiState.isCryDetectionActive ||
            uiState.isMotionDetectionActive ||
            recentAlerts.isNotEmpty() ||
            uiState.isCameraObstructed

    AnimatedVisibility(
        visible = shouldShow,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Camera obstruction warning (highest priority)
            if (uiState.isCameraObstructed) {
                ObstructionWarningCard()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Detection status card
            DetectionStatusCard(uiState = uiState)

            // Recent alerts (if any)
            if (recentAlerts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LatestAlertCard(alert = recentAlerts.first())
            }
        }
    }
}

@Composable
private fun DetectionStatusCard(uiState: DetectionUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Detection status. ")
                    if (uiState.isCryDetectionActive) append("Cry detection is active. ")
                    if (uiState.isMotionDetectionActive) append("Motion detection is active. ")
                    if (uiState.lastCryConfidence > 0) {
                        append("Last cry confidence: ${(uiState.lastCryConfidence * 100).toInt()} percent. ")
                    }
                    if (uiState.lastMotionConfidence > 0) {
                        append("Last motion confidence: ${(uiState.lastMotionConfidence * 100).toInt()} percent. ")
                    }
                }
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = ChildColors.Secondary.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(ChildColors.Monitoring)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Watching",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = ChildColors.SecondaryDark
                )
            }

            // Detection indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isCryDetectionActive) {
                    DetectionIndicator(
                        label = "Sound",
                        isActive = uiState.lastCryConfidence > 0.5f,
                        color = ChildColors.MomButton
                    )
                }
                if (uiState.isMotionDetectionActive) {
                    DetectionIndicator(
                        label = "Motion",
                        isActive = uiState.lastMotionConfidence > 0.15f,
                        color = ChildColors.DadButton
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectionIndicator(
    label: String,
    isActive: Boolean,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label detection ${if (isActive) "triggered" else "active"}"
        }
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) color else color.copy(alpha = 0.3f)
                )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) color else color.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ObstructionWarningCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Warning: Camera is blocked or obscured. Please make sure the camera can see the room."
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3CD)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF856404)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.detection_camera_blocked),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF856404)
                )
                Text(
                    text = stringResource(R.string.detection_camera_blocked_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF856404)
                )
            }
        }
    }
}

@Composable
private fun LatestAlertCard(alert: Alert) {
    val alertColor = when (alert.eventType) {
        AlertType.CRY_DETECTED -> ChildColors.MomButton
        AlertType.MOTION_DETECTED -> ChildColors.DadButton
        AlertType.SOS_ACTIVATED -> ChildColors.SosActive
        AlertType.CAMERA_OBSTRUCTED -> Color(0xFF856404)
        else -> ChildColors.Primary
    }

    val alertLabel = when (alert.eventType) {
        AlertType.CRY_DETECTED -> "Cry detected"
        AlertType.MOTION_DETECTED -> "Motion detected"
        AlertType.SOS_ACTIVATED -> "SOS activated"
        AlertType.CAMERA_OBSTRUCTED -> "Camera blocked"
        AlertType.DEVICE_OFFLINE -> "Device offline"
        AlertType.LOW_BATTERY -> "Low battery"
        AlertType.CALL_STARTED -> "Call started"
        AlertType.CALL_ENDED -> "Call ended"
        AlertType.THERMAL_WARNING -> "Thermal warning"
        AlertType.DEVICE_OVERHEATING -> "Device overheating"
        else -> ""
    }

    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(alert.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$alertLabel at $timeStr. " +
                        alert.confidence?.let { "Confidence ${(it * 100).toInt()} percent." }.orEmpty()
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = alertColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(alertColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = alertLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = alertColor
                    )
                    alert.confidence?.let { confidence ->
                        Text(
                            text = "Confidence: ${(confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = alertColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Text(
                text = timeStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
