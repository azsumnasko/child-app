package com.childhelper.app.child.ui.sos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childhelper.app.child.R
import com.childhelper.app.child.ui.theme.ChildColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SOS button with hold-to-activate behavior.
 *
 * Requirements:
 * - 2+ second long-press to activate
 * - Visual progress indicator during hold
 * - Vibration feedback on activation
 * - Warm amber/orange color (NOT alarming red)
 * - Large touch target (100dp minimum)
 * - TalkBack accessible
 */
@Composable
fun SosButton(
    onSosActivated: () -> Unit,
    modifier: Modifier = Modifier,
    holdDurationMs: Long = 2000L
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    var isActivated by remember { mutableStateOf(false) }

    // Progress animation
    LaunchedEffect(isPressed) {
        if (isPressed && !isActivated) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = holdDurationMs.toInt(),
                    easing = LinearEasing
                )
            )
        } else {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 200)
            )
        }
    }

    val scale = if (isPressed) 0.95f else 1f

    Box(
        modifier = modifier
            .scale(scale)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ChildColors.SosActive,
                        ChildColors.SosPressed
                    )
                )
            )
            .pointerInput(Unit) {
                coroutineScope {
                    while (true) {
                        awaitPointerEventScope {
                            // Wait for press
                            val down = awaitFirstDown()
                            isPressed = true

                            // Start hold timer
                            val holdJob = launch {
                                delay(holdDurationMs)
                                // Hold duration reached — activate SOS
                                if (!isActivated) {
                                    isActivated = true
                                    isPressed = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                    onSosActivated()
                                }
                            }

                            // Wait for release
                            val up = waitForUpOrCancellation()
                            holdJob.cancel()

                            if (up != null) {
                                // Released before hold duration — cancel
                                if (!isActivated) {
                                    isPressed = false
                                    // Short haptic to indicate cancelled
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    }
                }
            }
            .semantics {
                contentDescription = "SOS Emergency Button. Hold for 2 seconds to send emergency alert to guardians."
            },
        contentAlignment = Alignment.Center
    ) {
        // Progress ring
        if (isPressed && !isActivated) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(
                        Color.White.copy(alpha = 0.2f + (progress.value * 0.3f))
                    )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_sos),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "SOS",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            if (isPressed && !isActivated) {
                Text(
                    text = "${((1f - progress.value) * holdDurationMs / 1000f).toInt() + 1}s",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Compact SOS button for use in toolbars or smaller spaces.
 * Still maintains 56dp minimum touch target.
 */
@Composable
fun CompactSosButton(
    onSosActivated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    var isActivated by remember { mutableStateOf(false) }
    val holdDurationMs = 2000L

    LaunchedEffect(isPressed) {
        if (isPressed && !isActivated) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = holdDurationMs.toInt(),
                    easing = LinearEasing
                )
            )
        } else {
            progress.animateTo(0f, animationSpec = tween(200))
        }
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (isPressed) ChildColors.SosPressed
                else ChildColors.SosActive
            )
            .pointerInput(Unit) {
                coroutineScope {
                    while (true) {
                        awaitPointerEventScope {
                            awaitFirstDown()
                            isPressed = true
                            val holdJob = launch {
                                delay(holdDurationMs)
                                if (!isActivated) {
                                    isActivated = true
                                    isPressed = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                    onSosActivated()
                                }
                            }
                            val up = waitForUpOrCancellation()
                            holdJob.cancel()
                            if (up != null && !isActivated) {
                                isPressed = false
                            }
                        }
                    }
                }
            }
            .semantics {
                contentDescription = "SOS button. Hold for 2 seconds to activate emergency alert."
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_sos),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = Color.White
        )
    }
}
