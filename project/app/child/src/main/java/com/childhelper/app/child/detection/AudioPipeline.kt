package com.childhelper.app.child.detection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Process
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audio pipeline for real-time audio capture using AudioRecord.
 *
 * Privacy-first design:
 * - Uses AudioRecord (NOT MediaRecorder) for raw buffer access
 * - Audio buffers are immediately discarded after analysis
 * - NO audio is ever written to files or uploaded to cloud
 * - NO persistent audio storage on disk
 *
 * Configuration:
 * - Sample rate: 16kHz (optimized for voice/cry detection)
 * - Channel config: Mono
 * - Format: 16-bit PCM
 * - Buffer size: 2-second rolling windows
 */
class AudioPipeline(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: kotlinx.coroutines.Job? = null

    private val _audioBuffer = MutableSharedFlow<ByteArray>()
    val audioBuffer: Flow<ByteArray> = _audioBuffer.asSharedFlow()

    private val _isRecording = MutableSharedFlow<Boolean>(replay = 1)
    val isRecording: Flow<Boolean> = _isRecording.asSharedFlow()

    private var isRunning = false

    // Audio configuration
    companion object {
        const val SAMPLE_RATE = 16000 // 16kHz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2 // 16-bit
        const val WINDOW_SECONDS = 2
        const val SAMPLES_PER_WINDOW = SAMPLE_RATE * WINDOW_SECONDS // 32000 samples
        const val BYTES_PER_WINDOW = SAMPLES_PER_WINDOW * BYTES_PER_SAMPLE // 64000 bytes

        // Minimum buffer size for AudioRecord
        val MIN_BUFFER_SIZE: Int by lazy {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        }
    }

    /**
     * Start audio recording and stream buffers for analysis.
     * Requires RECORD_AUDIO permission.
     */
    fun startRecording() {
        if (isRunning) return
        if (!hasRecordPermission()) {
            android.util.Log.w("AudioPipeline", "Audio permission denied — cry detection disabled")
            return
        }

        try {
            val bufferSize = maxOf(MIN_BUFFER_SIZE * 2, BYTES_PER_WINDOW * 2)

            audioRecord = AudioRecord(
                1, // MIC
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            isRunning = true

            audioRecord?.startRecording()
            scope.launch { _isRecording.emit(true) }

            // Start the recording loop in a dedicated coroutine
            recordingJob = scope.launch(Dispatchers.IO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                val readBuffer = ByteArray(BYTES_PER_WINDOW)
                var bufferOffset = 0

                while (isActive && isRunning) {
                    val audioRecordInstance = audioRecord ?: break
                    if (audioRecordInstance.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        break
                    }

                    val bytesToRead = BYTES_PER_WINDOW - bufferOffset
                    val bytesRead = audioRecordInstance.read(readBuffer, bufferOffset, bytesToRead)

                    if (bytesRead > 0) {
                        bufferOffset += bytesRead

                        // When we have a full window, emit it
                        if (bufferOffset >= BYTES_PER_WINDOW) {
                            // Copy the buffer — the original will be overwritten
                            val windowCopy = readBuffer.copyOf(BYTES_PER_WINDOW)

                            // Emit for analysis
                            _audioBuffer.emit(windowCopy)

                            // Reset for next window
                            bufferOffset = 0
                        }
                    } else if (bytesRead < 0) {
                        // Error reading from AudioRecord
                        delay(100)
                    }
                }
            }
        } catch (e: SecurityException) {
            isRunning = false
            scope.launch { _isRecording.emit(false) }
        } catch (e: Exception) {
            isRunning = false
            scope.launch { _isRecording.emit(false) }
        }
    }

    /**
     * Stop recording and release the AudioRecord instance.
     * All pending buffers are discarded.
     */
    fun stopRecording() {
        isRunning = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Best effort cleanup
        }
        audioRecord = null

        scope.launch { _isRecording.emit(false) }
    }

    /**
     * Check if the app has RECORD_AUDIO permission.
     */
    fun hasRecordPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get the raw PCM buffer size for one analysis window.
     */
    fun getWindowSizeBytes(): Int = BYTES_PER_WINDOW

    /**
     * Get the sample rate of the audio pipeline.
     */
    fun getSampleRate(): Int = SAMPLE_RATE

    /**
     * Convert a raw PCM byte buffer to a normalized float array
     * suitable for model input.
     *
     * @param pcmBuffer Raw 16-bit PCM data
     * @return FloatArray with values in range [-1.0, 1.0]
     */
    fun pcmToFloatArray(pcmBuffer: ByteArray): FloatArray {
        val sampleCount = pcmBuffer.size / BYTES_PER_SAMPLE
        val floatArray = FloatArray(sampleCount)

        for (i in 0 until sampleCount) {
            val low = pcmBuffer[i * 2].toInt() and 0xFF
            val high = pcmBuffer[i * 2 + 1].toInt()
            val sample = (high shl 8 or low).toShort().toInt()
            floatArray[i] = sample / 32768.0f
        }

        return floatArray
    }
}
