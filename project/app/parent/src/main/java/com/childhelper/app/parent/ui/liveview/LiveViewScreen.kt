package com.childhelper.app.parent.ui.liveview

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.childhelper.app.parent.R
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.TimeUnit

/**
 * Live View Screen — WebRTC video streaming with controls.
 *
 * Features:
 * - Full-screen video renderer (SurfaceViewRenderer)
 * - Audio toggle, video toggle
 * - Video/audio-only mode selection
 * - Adaptive quality indicator
 * - Connection state feedback with visual indicators
 * - Talk-back (two-way audio) toggle
 * - Connection duration timer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveViewScreen(
    onNavigateBack: () -> Unit,
    viewModel: LiveViewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Request audio permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Handle permission denied
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-start connection on first launch
    LaunchedEffect(Unit) {
        if (uiState.connectionState == LiveConnectionState.IDLE) {
            viewModel.startConnection()
        }
    }

    // Lifecycle handling
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (uiState.connectionState == LiveConnectionState.DISCONNECTED) {
                        viewModel.retryConnection()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Optionally pause video to save bandwidth
                }
                Lifecycle.Event.ON_DESTROY -> {
                    viewModel.disconnect()
                }
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Error snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            if (!uiState.isFullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.live_view_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.disconnect()
                            onNavigateBack()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back_description)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            // Video renderer
            VideoRenderer(
                isVideoEnabled = uiState.isVideoEnabled &&
                    uiState.connectionState == LiveConnectionState.CONNECTED,
                modifier = Modifier.fillMaxSize()
            )

            // Connection state overlay
            ConnectionStateOverlay(
                connectionState = uiState.connectionState,
                modifier = Modifier.fillMaxSize()
            )

            // Controls overlay (bottom)
            if (!uiState.isFullscreen || uiState.connectionState == LiveConnectionState.CONNECTED) {
                ControlsOverlay(
                    uiState = uiState,
                    onToggleAudio = { viewModel.toggleAudio() },
                    onToggleVideo = { viewModel.toggleVideo() },
                    onToggleTalkBack = { viewModel.toggleTalkBack() },
                    onSetStreamMode = { viewModel.setStreamMode(it) },
                    onDisconnect = {
                        viewModel.disconnect()
                        onNavigateBack()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Quality indicator (top-right)
            if (uiState.connectionState == LiveConnectionState.CONNECTED) {
                QualityIndicator(
                    quality = uiState.videoQuality,
                    durationMs = uiState.connectionDurationMs,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = if (uiState.isFullscreen) 16.dp else 0.dp)
                )
            }

            // Connection dialog
            if (uiState.showConnectionDialog) {
                ConnectionProgressDialog(
                    connectionState = uiState.connectionState,
                    onDismiss = { viewModel.dismissConnectionDialog() },
                    onRetry = { viewModel.retryConnection() }
                )
            }
        }
    }
}

/**
 * WebRTC video renderer using SurfaceViewRenderer.
 */
@Composable
private fun VideoRenderer(
    isVideoEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (isVideoEnabled) {
        val eglBase = remember { EglBase.create() }
        val surfaceView = remember {
            SurfaceViewRenderer(context).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                surfaceView.release()
                eglBase.release()
            }
        }

        AndroidView(
            factory = { surfaceView },
            modifier = modifier.semantics {
                contentDescription = "Live video stream from child device"
            }
        )
    } else {
        // Placeholder when video is disabled
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Video Disabled",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

/**
 * Connection state overlay showing status during connection.
 */
@Composable
private fun ConnectionStateOverlay(
    connectionState: LiveConnectionState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = connectionState != LiveConnectionState.CONNECTED &&
            connectionState != LiveConnectionState.CLOSED,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (connectionState) {
                    LiveConnectionState.CONNECTING,
                    LiveConnectionState.SIGNALING,
                    LiveConnectionState.RECONNECTING -> {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when (connectionState) {
                                LiveConnectionState.CONNECTING -> stringResource(R.string.live_view_connecting)
                                LiveConnectionState.SIGNALING -> "Negotiating..."
                                LiveConnectionState.RECONNECTING -> "Reconnecting..."
                                else -> ""
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    LiveConnectionState.DISCONNECTED -> {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connection Lost",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { /* retry in dialog */ }
                        ) {
                            Text("Reconnect", color = Color.White)
                        }
                    }
                    LiveConnectionState.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = null,
                            tint = Color.Red.copy(alpha = 0.7f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Connection Failed",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    else -> { /* no-op */ }
                }
            }
        }
    }
}

/**
 * Bottom controls overlay.
 */
@Composable
private fun ControlsOverlay(
    uiState: LiveViewUiState,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleTalkBack: () -> Unit,
    onSetStreamMode: (StreamMode) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Stream mode selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StreamMode.values().forEach { mode ->
                val selected = uiState.streamMode == mode
                val label = when (mode) {
                    StreamMode.VIDEO_AUDIO -> "Video + Audio"
                    StreamMode.AUDIO_ONLY -> "Audio Only"
                    StreamMode.VIDEO_ONLY -> "Video Only"
                }
                TextButton(
                    onClick = { onSetStreamMode(mode) }
                ) {
                    Text(
                        text = label,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.6f),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Main controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Audio toggle
            ControlButton(
                icon = if (uiState.isAudioEnabled) Icons.Default.VolumeUp
                else Icons.Default.VolumeOff,
                contentDescription = if (uiState.isAudioEnabled) "Mute audio"
                else "Unmute audio",
                onClick = onToggleAudio,
                isActive = uiState.isAudioEnabled
            )

            // Video toggle
            ControlButton(
                icon = if (uiState.isVideoEnabled) Icons.Default.Videocam
                else Icons.Default.VideocamOff,
                contentDescription = if (uiState.isVideoEnabled) "Turn off video"
                else "Turn on video",
                onClick = onToggleVideo,
                isActive = uiState.isVideoEnabled
            )

            // Disconnect (end call)
            FilledIconButton(
                onClick = onDisconnect,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Red
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "End live view",
                    modifier = Modifier.size(28.dp)
                )
            }

            // Talk-back toggle
            ControlButton(
                icon = if (uiState.isTalkBackEnabled) Icons.Default.Mic
                else Icons.Default.MicOff,
                contentDescription = if (uiState.isTalkBackEnabled) "Disable talk-back"
                else "Enable talk-back",
                onClick = onToggleTalkBack,
                isActive = uiState.isTalkBackEnabled,
                showAudioLevel = uiState.isTalkBackEnabled,
                audioLevel = uiState.talkBackAudioLevel
            )

            // Hearing/speaker mode (placeholder for speaker toggle)
            ControlButton(
                icon = Icons.Default.Hearing,
                contentDescription = "Speaker mode",
                onClick = { /* toggle speaker */ },
                isActive = false
            )
        }
    }
}

/**
 * Individual control button.
 */
@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    showAudioLevel: Boolean = false,
    audioLevel: Float = 0f
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        if (showAudioLevel && audioLevel > 0.05f) {
            LinearProgressIndicator(
                progress = { audioLevel },
                modifier = Modifier
                    .width(40.dp)
                    .height(2.dp)
                    .padding(bottom = 2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) Color.White else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Video quality and duration indicator.
 */
@Composable
private fun QualityIndicator(
    quality: VideoQuality,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    val qualityColor = when (quality) {
        VideoQuality.HIGH -> Color(0xFF4CAF50)
        VideoQuality.MEDIUM -> Color(0xFFFFC107)
        VideoQuality.LOW -> Color(0xFFF44336)
    }
    val qualityLabel = when (quality) {
        VideoQuality.HIGH -> "HD"
        VideoQuality.MEDIUM -> "SD"
        VideoQuality.LOW -> "LOW"
    }

    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    val durationStr = String.format("%02d:%02d", minutes, seconds)

    Row(
        modifier = modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Live indicator dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.Red)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "LIVE",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = qualityLabel,
            color = qualityColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = durationStr,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Connection progress dialog.
 */
@Composable
private fun ConnectionProgressDialog(
    connectionState: LiveConnectionState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (connectionState) {
                    LiveConnectionState.CONNECTING -> stringResource(R.string.live_view_connecting)
                    LiveConnectionState.RECONNECTING -> "Reconnecting..."
                    LiveConnectionState.FAILED -> "Connection Failed"
                    else -> stringResource(R.string.live_view_connecting)
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (connectionState == LiveConnectionState.CONNECTING ||
                    connectionState == LiveConnectionState.RECONNECTING
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Establishing secure connection to child device...",
                        textAlign = TextAlign.Center
                    )
                } else if (connectionState == LiveConnectionState.FAILED) {
                    Text(
                        text = "Could not connect to the child device. Please check the device status and try again.",
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            if (connectionState == LiveConnectionState.FAILED) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
