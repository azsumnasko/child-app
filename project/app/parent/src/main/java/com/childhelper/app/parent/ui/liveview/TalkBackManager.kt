package com.childhelper.app.parent.ui.liveview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages two-way audio communication between parent and child devices.
 *
 * - Parent microphone audio is captured locally and sent via WebRTC data channel
 *   to the child device where it's played through the speaker.
 * - This enables the parent to talk to the child ("talk-back" feature).
 *
 * PRIVACY: Audio is streamed in real-time only — no recording, no persistent storage.
 * Uses WebRTC data channel for low-latency audio transport.
 */
@Singleton
class TalkBackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- State ---
    private val _isTalkBackEnabled = MutableStateFlow(false)
    val isTalkBackEnabled: StateFlow<Boolean> = _isTalkBackEnabled.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // --- Audio recording ---
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // --- WebRTC ---
    private var dataChannel: DataChannel? = null
    private var localAudioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null

    // Audio configuration
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            .coerceAtLeast(sampleRate / 10) // At least 100ms buffer
    }

    companion object {
        private const val TAG = "TalkBackManager"
        private const val AUDIO_CHUNK_SIZE = 640 // 20ms at 16kHz mono 16-bit
    }

    /**
     * Initialize with an active peer connection and data channel.
     * Must be called before enabling talk-back.
     */
    fun initialize(
        connection: PeerConnection,
        channel: DataChannel? = null
    ) {
        this.peerConnection = connection
        this.dataChannel = channel
    }

    /**
     * Enable or disable the talk-back feature.
     * When enabled, captures microphone audio and sends it to the child.
     */
    fun setTalkBackEnabled(enabled: Boolean) {
        if (enabled == _isTalkBackEnabled.value) return

        if (enabled) {
            startTalkBack()
        } else {
            stopTalkBack()
        }
        _isTalkBackEnabled.value = enabled
    }

    /**
     * Toggle talk-back on/off.
     */
    fun toggleTalkBack() {
        setTalkBackEnabled(!_isTalkBackEnabled.value)
    }

    /**
     * Start capturing parent microphone audio and sending via data channel.
     */
    private fun startTalkBack() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        managerScope.launch(Dispatchers.IO) {
            try {
                val record = AudioRecord(
                    7, // VOICE_COMMUNICATION
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    return@launch
                }

                audioRecord = record
                record.startRecording()

                recordingJob = launch {
                    val buffer = ByteArray(AUDIO_CHUNK_SIZE)
                    while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            // Calculate audio level for UI indicator
                            val level = calculateAudioLevel(buffer, read)
                            _audioLevel.value = level

                            // Send via data channel
                            sendAudioChunk(buffer.copyOfRange(0, read))
                        }
                        // Small delay to prevent CPU spinning
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                _isTalkBackEnabled.value = false
            }
        }
    }

    /**
     * Stop talk-back audio capture.
     */
    private fun stopTalkBack() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (_: Exception) {
            }
        }
        audioRecord = null
        _audioLevel.value = 0f
    }

    /**
     * Send an audio chunk through the WebRTC data channel.
     */
    private fun sendAudioChunk(data: ByteArray) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return

        try {
            val buffer = DataChannel.Buffer(
                ByteBuffer.wrap(data),
                true // Binary data
            )
            channel.send(buffer)
        } catch (_: Exception) {
            // Data channel may have closed
        }
    }

    /**
     * Calculate audio level (0.0 - 1.0) from raw PCM data for UI visualization.
     */
    private fun calculateAudioLevel(buffer: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0L
        var count = 0
        var i = 0
        while (i < length - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val signed = if (sample >= 32768) sample - 65536 else sample
            sum += kotlin.math.abs(signed)
            count++
            i += 2
        }
        if (count == 0) return 0f
        val average = sum / count
        return (average / 32768f).coerceIn(0f, 1f)
    }

    /**
     * Release all resources. Call when the live view is closed.
     */
    fun release() {
        stopTalkBack()
        dataChannel?.close()
        dataChannel = null
        peerConnection = null
        managerScope.cancel()
    }
}
