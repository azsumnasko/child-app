package com.childhelper.app.child.ui.call

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
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.childhelper.app.child.R
import com.childhelper.app.child.ui.theme.ChildColors
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Call screen for the child app.
 *
 * Features:
 * - WebRTC video + audio display
 * - One-tap calling with large buttons
 * - Audio-only fallback when camera unavailable
 * - Large accessible control buttons (72dp minimum)
 * - Mute, video toggle, camera switch, speaker toggle
 * - Call duration timer
 * - TalkBack content descriptions for all controls
 */
@Composable
fun CallScreen(
    navController: NavController,
    contactId: String,
    hasVideo: Boolean = true,
    viewModel: CallViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Start the call on first composition
    DisposableEffect(contactId) {
        viewModel.startCall(contactId, hasVideo)
        onDispose {
            viewModel.endCall()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Remote video (full screen background) or audio placeholder
        if (uiState.hasVideo && !uiState.isAudioOnly && !uiState.isVideoOff) {
            val remoteTrack = uiState.remoteVideoTrack
            if (remoteTrack != null) {
                RemoteVideoView(
                    videoTrack = remoteTrack,
                    eglBase = viewModel.getEglBase(),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // No remote video yet — show connecting placeholder
                ConnectingPlaceholder(
                    contactName = uiState.contactName,
                    status = uiState.status
                )
            }
        } else {
            // Audio-only or video off — show large contact info
            AudioOnlyView(
                contactName = uiState.contactName,
                callDuration = uiState.callDuration,
                status = uiState.status
            )
        }

        // Call info overlay (top)
        CallInfoOverlay(
            contactName = uiState.contactName,
            callDuration = uiState.callDuration,
            status = uiState.status,
            isAudioOnly = uiState.isAudioOnly || uiState.isVideoOff,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Call controls (bottom)
        CallControls(
            uiState = uiState,
            onMuteToggle = { viewModel.toggleMute() },
            onVideoToggle = { viewModel.toggleVideo() },
            onCameraSwitch = { viewModel.switchCamera() },
            onSpeakerToggle = { viewModel.toggleSpeaker() },
            onEndCall = { viewModel.endCall() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun RemoteVideoView(
    videoTrack: VideoTrack,
    eglBase: org.webrtc.EglBase?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceView = remember {
        SurfaceViewRenderer(context).apply {
            eglBase?.eglBaseContext?.let { init(it, null) }
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            videoTrack.addSink(this)
        }
    }

    AndroidView(
        factory = { surfaceView },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            videoTrack.removeSink(surfaceView)
            surfaceView.release()
        }
    }
}

@Composable
private fun ConnectingPlaceholder(
    contactName: String,
    status: CallStatusUi
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1C1E),
                        Color(0xFF2C2E31)
                    )
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Profile circle
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(ChildColors.Primary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contactName.firstOrNull()?.toString() ?: "?",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = ChildColors.PrimaryLight,
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(
                when (status) {
                    CallStatusUi.CONNECTING -> R.string.call_connecting
                    CallStatusUi.RINGING -> R.string.call_ringing
                    else -> R.string.call_please_wait
                }
            ),
            fontSize = 20.sp,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun AudioOnlyView(
    contactName: String,
    callDuration: String,
    status: CallStatusUi
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1C1E),
                        Color(0xFF2C2E31)
                    )
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(ChildColors.Primary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = contactName,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = callDuration,
            fontSize = 20.sp,
            color = Color.White.copy(alpha = 0.7f)
        )

        if (status == CallStatusUi.CONNECTING || status == CallStatusUi.RINGING) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = ChildColors.PrimaryLight
            )
        }
    }
}

@Composable
private fun CallInfoOverlay(
    contactName: String,
    callDuration: String,
    status: CallStatusUi,
    isAudioOnly: Boolean,
    modifier: Modifier = Modifier
) {
    val infoFormat = stringResource(R.string.call_info_format, contactName, callDuration)
    val audioOnlySuffix = stringResource(R.string.call_audio_only_suffix)
    val statusSuffix = stringResource(R.string.call_status_suffix, status.name.lowercase())

    val callInfoDesc = buildString {
        append(infoFormat)
        if (isAudioOnly) {
            append(" ")
            append(audioOnlySuffix)
        }
        append(" ")
        append(statusSuffix)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = callInfoDesc
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isAudioOnly) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xCC1A1C1E)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = ChildColors.PrimaryLight
                    )
                    Text(
                        text = stringResource(R.string.call_audio_only),
                        fontSize = 14.sp,
                        color = ChildColors.PrimaryLight
                    )
                }
            }
        }
    }
}

@Composable
private fun CallControls(
    uiState: CallUiState,
    onMuteToggle: () -> Unit,
    onVideoToggle: () -> Unit,
    onCameraSwitch: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Call duration
        if (uiState.status == CallStatusUi.CONNECTED) {
            Text(
                text = uiState.callDuration,
                fontSize = 18.sp,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Control buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute button
            CallControlButton(
                onClick = onMuteToggle,
                icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = stringResource(if (uiState.isMuted) R.string.call_unmute_description else R.string.call_mute_description),
                isActive = !uiState.isMuted,
                activeColor = ChildColors.Secondary
            )

            // Video toggle button (only if video was enabled)
            if (uiState.hasVideo && !uiState.isAudioOnly) {
                CallControlButton(
                    onClick = onVideoToggle,
                    icon = if (uiState.isVideoOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    contentDescription = stringResource(if (uiState.isVideoOff) R.string.call_video_on_description else R.string.call_video_off_description),
                    isActive = !uiState.isVideoOff,
                    activeColor = ChildColors.Primary
                )
            }

            // Camera switch button
            if (uiState.hasVideo && !uiState.isAudioOnly && !uiState.isVideoOff) {
                CallControlButton(
                    onClick = onCameraSwitch,
                    icon = Icons.Default.Cameraswitch,
                    contentDescription = stringResource(R.string.call_switch_camera_description),
                    isActive = true,
                    activeColor = ChildColors.Primary
                )
            }

            // Speaker toggle button
            CallControlButton(
                onClick = onSpeakerToggle,
                icon = if (uiState.isSpeakerOn) Icons.Default.Speaker else Icons.Default.VolumeOff,
                contentDescription = stringResource(if (uiState.isSpeakerOn) R.string.call_speaker_on_description else R.string.call_speaker_off_description),
                isActive = uiState.isSpeakerOn,
                activeColor = ChildColors.Primary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // End call button — large and prominent
        val endCallDesc = stringResource(R.string.call_end_description)
        IconButton(
            onClick = onEndCall,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFFD9534F))
                .semantics {
                    contentDescription = endCallDesc
                }
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Color.White
            )
        }
    }
}

/**
 * Individual call control button with consistent styling.
 * Minimum 64dp touch target for easy use by children.
 */
@Composable
private fun CallControlButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    activeColor: Color
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(
                if (isActive) Color(0xCC3A3D42) else Color(0x665B9BD5)
            )
            .semantics {
                this.contentDescription = contentDescription
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (isActive) Color.White else Color.White.copy(alpha = 0.5f)
        )
    }
}
