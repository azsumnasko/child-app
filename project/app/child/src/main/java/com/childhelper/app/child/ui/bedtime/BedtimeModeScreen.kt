package com.childhelper.app.child.ui.bedtime

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.childhelper.app.child.ui.theme.BedtimeTheme
import com.childhelper.app.child.ui.theme.ChildColors

/**
 * Bedtime Mode screen for the child app.
 *
 * Features:
 * - Dark theme with deep calming colors (deep blue-purple)
 * - Screen brightness dimming via WindowManager
 * - Calming voice messages spoken periodically
 * - Auto-answer toggle for incoming calls
 * - Moon/stars visual elements
 * - Large exit button for easy use
 * - TalkBack accessible with full content descriptions
 */
@Composable
fun BedtimeModeScreen(
    navController: NavController,
    viewModel: BedtimeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context as? Activity }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Apply screen brightness dimming
    LaunchedEffect(uiState.screenBrightness) {
        val window = activity?.window
        if (window != null) {
            val layoutParams = window.attributes
            layoutParams.screenBrightness = uiState.screenBrightness
            window.attributes = layoutParams
        }
    }

    // Restore brightness on exit
    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window
            if (window != null) {
                val layoutParams = window.attributes
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = layoutParams
            }
            viewModel.endBedtimeSession()
        }
    }

    // Start bedtime session when the screen is composed
    LaunchedEffect(Unit) {
        viewModel.startBedtimeSession(lifecycleOwner)
    }

    BedtimeTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            ChildColors.BedtimeBackground,
                            ChildColors.BedtimeGradientEnd
                        )
                    )
                )
        ) {
            // Animated stars background
            StarsBackground()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top bar with moon icon and status
                BedtimeHeader(
                    isActive = uiState.isActive,
                    onExit = { navController.popBackStack() }
                )

                // Center content with moon and message
                BedtimeCenterContent(
                    isActive = uiState.isActive,
                    onPlayMessage = { viewModel.playCalmingMessage() }
                )

                // Bottom controls
                BedtimeControls(
                    uiState = uiState,
                    onBrightnessChange = { viewModel.setBrightness(it) },
                    onAutoAnswerToggle = { viewModel.toggleAutoAnswer(it) }
                )
            }
        }
    }
}

@Composable
private fun BedtimeHeader(
    isActive: Boolean,
    onExit: () -> Unit
) {
    val headerDesc = stringResource(
        if (isActive) R.string.bedtime_header_description_active
        else R.string.bedtime_header_description_starting
    )
    val exitDesc = stringResource(R.string.bedtime_exit_description)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Moon icon with glow
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = headerDesc
            }
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ChildColors.BedtimeAccent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nightlight,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = ChildColors.BedtimeAccent
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.bedtime_mode_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = ChildColors.BedtimeAccent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(if (isActive) R.string.bedtime_mode_active else R.string.bedtime_mode_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8B8FA3)
                )
            }
        }

        // Exit button — large and accessible
        IconButton(
            onClick = onExit,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFF3A3F58))
                .semantics {
                    contentDescription = exitDesc
                }
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color(0xFFC5C9D6),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun BedtimeCenterContent(
    isActive: Boolean,
    onPlayMessage: () -> Unit
) {
    val moonScale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.9f,
        animationSpec = tween(3000, easing = LinearEasing),
        label = "moonBreathing"
    )

    val moonDesc = stringResource(R.string.bedtime_moon_description)
    val playDesc = stringResource(R.string.bedtime_play_message_description)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = moonDesc
        }
    ) {
        // Breathing moon animation
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(moonScale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ChildColors.BedtimeAccent.copy(alpha = 0.4f),
                            ChildColors.BedtimeAccent.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Nightlight,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = ChildColors.BedtimeAccent.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Calming message
        Text(
            text = stringResource(R.string.bedtime_calm_message),
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFC5C9D6),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Light
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.bedtime_calm_submessage),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF8B8FA3),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tap to hear message button
        IconButton(
            onClick = onPlayMessage,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color(0xFF3A3F58))
                .semantics {
                    contentDescription = playDesc
                }
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                tint = Color(0xFF8B8FA3),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun BedtimeControls(
    uiState: BedtimeUiState,
    onBrightnessChange: (Float) -> Unit,
    onAutoAnswerToggle: (Boolean) -> Unit
) {
    val brightnessDesc = stringResource(R.string.bedtime_brightness_slider_description, (uiState.screenBrightness * 100).toInt())

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = ChildColors.BedtimeSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Screen brightness slider
            Text(
                text = stringResource(R.string.bedtime_screen_brightness),
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFFC5C9D6),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = uiState.screenBrightness,
                onValueChange = onBrightnessChange,
                valueRange = 0.05f..0.5f,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = brightnessDesc
                    },
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = ChildColors.BedtimeAccent,
                    activeTrackColor = ChildColors.BedtimeAccent,
                    inactiveTrackColor = Color(0xFF3A3F58)
                )
            )
            Text(
                text = "${(uiState.screenBrightness * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8B8FA3),
                modifier = Modifier.align(Alignment.End)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-answer toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Auto-answer calls is ${if (uiState.autoAnswerEnabled) "on" else "off"}. When on, incoming calls from Mom or Dad will be answered automatically after 2 seconds."
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.bedtime_auto_answer_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFC5C9D6),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.bedtime_auto_answer_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B8FA3)
                    )
                }
                Switch(
                    checked = uiState.autoAnswerEnabled,
                    onCheckedChange = onAutoAnswerToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ChildColors.BedtimeAccent,
                        checkedTrackColor = ChildColors.BedtimeAccent.copy(alpha = 0.5f),
                        uncheckedThumbColor = Color(0xFF8B8FA3),
                        uncheckedTrackColor = Color(0xFF3A3F58)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Monitoring status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = if (uiState.isMonitoring) {
                        "Cry and motion monitoring is active during bedtime."
                    } else {
                        "Monitoring will start automatically when you fall asleep."
                    }
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (uiState.isMonitoring) ChildColors.Online
                            else Color(0xFF8B8FA3)
                        )
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(if (uiState.isMonitoring) R.string.bedtime_listening_active else R.string.bedtime_listening_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8B8FA3)
                )
            }
        }
    }
}

/**
 * Simple animated stars background composable.
 */
@Composable
private fun StarsBackground() {
    val starAlphas = remember {
        List(20) { androidx.compose.animation.core.Animatable((it % 5 + 3) / 10f) }
    }

    // Animate each star's alpha
    starAlphas.forEachIndexed { index, animatable ->
        LaunchedEffect(index) {
            while (true) {
                animatable.animateTo(
                    targetValue = 0.1f + (kotlin.random.Random.nextFloat() * 0.5f),
                    animationSpec = tween(
                        durationMillis = 2000 + kotlin.random.Random.nextInt(2000),
                        easing = LinearEasing
                    )
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        starAlphas.forEachIndexed { index, alpha ->
            val x = remember(index) { kotlin.random.Random.nextFloat() }
            val y = remember(index) { kotlin.random.Random.nextFloat() }
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .alpha(alpha.value)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.8f))
                    .align(
                        BiasAlignment(
                            x * 2f - 1f,
                            y * 2f - 1f
                        )
                    )
            )
        }
    }
}