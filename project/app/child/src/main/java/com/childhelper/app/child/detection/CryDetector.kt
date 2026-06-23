package com.childhelper.app.child.detection

import android.util.Log
import com.childhelper.core.common.model.CryDetectionEvent
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.security.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max

/**
 * Cry detector using on-device ML inference.
 *
 * Pipeline:
 * 1. Receives 2-second rolling audio windows from AudioPipeline (16kHz mono PCM)
 * 2. Preprocesses audio: converts PCM to float, applies normalization
 * 3. Runs LiteRT quantized INT8 model inference via TfliteRunner
 * 4. Applies sustained-confidence logic: 3+ consecutive windows at >0.7 confidence
 * 5. Emits CryDetectionEvent with metadata (confidence, consecutive count)
 *
 * Audio-only mode:
 * When [setAudioOnlyMode] is called with `true`, the detector continues audio-based
 * cry detection even when the camera pipeline is disabled (e.g., critical low-power
 * or thermal states). Audio recording remains active; only video analysis is paused.
 *
 * Privacy: Raw audio buffers are discarded immediately after analysis.
 * Only metadata (confidence, timestamp) is emitted — no audio data leaves this class.
 *
 * @param audioPipeline Source of audio buffers
 * @param tfliteRunner TensorFlow Lite model runner
 * @param scope Coroutine scope for detection processing
 * @param securePreferences Secure storage for device ID retrieval
 */
class CryDetector(
    private val audioPipeline: AudioPipeline,
    private val tfliteRunner: TfliteRunner,
    private val scope: CoroutineScope,
    private val securePreferences: SecurePreferences
) {

    private val _cryEvents = MutableSharedFlow<CryDetectionEvent>()
    val cryEvents: Flow<CryDetectionEvent> = _cryEvents.asSharedFlow()

    private var detectionJob: Job? = null
    @Volatile var isRunning: Boolean = false
        private set

    /** Whether the detector is running in audio-only mode (no camera required). */
    var isAudioOnlyMode: Boolean = false
        private set

    // Detection state
    private var config: DetectionConfig = DetectionConfig()
    private var consecutivePositiveWindows = 0
    private var lastConfidence = 0f
    private val stateMutex = Mutex()

    companion object {
        private const val TAG = "CryDetector"

        // Default threshold for cry detection (0.0 - 1.0)
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.70f

        // Required consecutive positive windows to trigger an event
        private const val DEFAULT_CONSECUTIVE_WINDOWS = 3

        // Number of consecutive negative windows before resetting counter
        private const val NEGATIVE_WINDOW_RESET_COUNT = 2
    }

    /**
     * Start cry detection with the given configuration.
     *
     * @param config Detection configuration including sensitivity and thresholds
     */
    fun startDetection(config: DetectionConfig) {
        if (isRunning) return
        if (!audioPipeline.hasRecordPermission()) {
            Log.w(TAG, "Cannot start cry detection: RECORD_AUDIO permission not granted")
            return
        }

        this.config = config
        isRunning = true
        consecutivePositiveWindows = 0
        lastConfidence = 0f

        // Start audio pipeline
        audioPipeline.startRecording()

        // Begin processing audio buffers
        detectionJob = scope.launch(Dispatchers.Default) {
            audioPipeline.audioBuffer.collect { pcmBuffer ->
                if (!isRunning) return@collect
                processAudioWindow(pcmBuffer)
            }
        }
    }

    /**
     * Enable or disable audio-only detection mode.
     *
     * In audio-only mode, cry detection continues using audio analysis only,
     * without requiring the camera pipeline. This is used when:
     * - Battery drops below critical threshold (< 10%)
     * - Device enters thermal HOT state (video disabled)
     * - Any other condition that requires disabling video but keeping audio active
     *
     * Audio-only mode takes effect on the next [startDetection] call. If detection
     * is already running when this is called, the mode change is recorded but the
     * running detection is not interrupted.
     *
     * @param enabled `true` to enable audio-only mode, `false` for normal operation.
     */
    fun setAudioOnlyMode(enabled: Boolean) {
        isAudioOnlyMode = enabled
        Log.i(TAG, "Audio-only mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Stop cry detection and clean up.
     */
    fun stopDetection() {
        isRunning = false
        detectionJob?.cancel()
        detectionJob = null
        audioPipeline.stopRecording()
        consecutivePositiveWindows = 0
        lastConfidence = 0f
    }

    /**
     * Process a single 2-second audio window.
     *
     * @param pcmBuffer Raw 16-bit PCM audio data (64000 bytes for 2s @ 16kHz)
     */
    private suspend fun processAudioWindow(pcmBuffer: ByteArray) {
        try {
            // Step 1: Convert PCM to normalized float array
            val floatSamples = audioPipeline.pcmToFloatArray(pcmBuffer)

            // Step 2: Prepare quantized INT8 input for TFLite
            val quantizedInput = quantizeToInt8(floatSamples)

            // Step 3: Run model inference
            val output = tfliteRunner.runInference(quantizedInput)

            // Step 4: Interpret results (assumes binary classification: [notCry, cry])
            val cryConfidence = if (output.size >= 2) {
                // Softmax over outputs
                val exp0 = kotlin.math.exp(output[0].toDouble())
                val exp1 = kotlin.math.exp(output[1].toDouble())
                (exp1 / (exp0 + exp1)).toFloat()
            } else if (output.isNotEmpty()) {
                // Single output: sigmoid
                1f / (1f + kotlin.math.exp(-output[0]))
            } else {
                0f
            }

            // Step 5: Apply sustained-confidence logic
            stateMutex.withLock {
                val threshold = config.cryThreshold.takeIf { it > 0 } ?: DEFAULT_CONFIDENCE_THRESHOLD
                val requiredConsecutive = config.cryConsecutiveWindows.takeIf { it > 0 }
                    ?: DEFAULT_CONSECUTIVE_WINDOWS

                if (cryConfidence >= threshold) {
                    consecutivePositiveWindows++
                    lastConfidence = cryConfidence

                    // Check if we've reached the threshold for event emission
                    if (consecutivePositiveWindows >= requiredConsecutive) {
                        emitCryEvent(cryConfidence, consecutivePositiveWindows)
                        // Keep counting but don't re-emit immediately
                        // Reset after a larger number to allow re-detection
                        if (consecutivePositiveWindows >= requiredConsecutive + 5) {
                            consecutivePositiveWindows = 0
                        }
                    }
                } else {
                    // Decrement on negative detection (with floor)
                    consecutivePositiveWindows = max(0, consecutivePositiveWindows - 1)
                    lastConfidence = cryConfidence
                }
            }

            // Step 6: Discard the raw buffer (it's automatically garbage collected)
            // No explicit storage — the pcmBuffer parameter goes out of scope

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio window", e)
        }
    }

    /**
     * Quantize float audio samples to INT8 for the TFLite model.
     *
     * @param floatSamples Normalized float array [-1.0, 1.0]
     * @return ByteBuffer with INT8 quantized data
     */
    private fun quantizeToInt8(floatSamples: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(floatSamples.size)
            .order(ByteOrder.nativeOrder())

        for (sample in floatSamples) {
            // Quantize [-1.0, 1.0] to [-128, 127]
            val quantized = (sample * 127f).toInt().coerceIn(-128, 127)
            buffer.put(quantized.toByte())
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Emit a cry detection event.
     *
     * @param confidence Model confidence score (0.0 - 1.0)
     * @param consecutiveWindows Number of consecutive positive windows
     */
    private suspend fun emitCryEvent(confidence: Float, consecutiveWindows: Int) {
        val event = CryDetectionEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            confidence = confidence,
            consecutiveWindows = consecutiveWindows,
            childDeviceId = getDeviceId()
        )

        _cryEvents.emit(event)
        Log.i(TAG, "Cry detected: confidence=$confidence, consecutive=$consecutiveWindows")
    }

    /**
     * Read the device ID from secure preferences.
     * Falls back to a generated UUID if no device ID is stored.
     *
     * @return The child device identifier used to tag detection events.
     */
    private suspend fun getDeviceId(): String {
        return securePreferences.getString("device_id", "")?.ifEmpty {
            UUID.randomUUID().toString().also { fallbackId ->
                // Attempt to persist the fallback ID for future consistency.
                // Failure is non-critical — we just use the transient UUID.
                try {
                    securePreferences.putString("device_id", fallbackId)
                } catch (_: Exception) {
                    // Secure storage unavailable; transient ID is acceptable.
                }
            }
        } ?: "child_device"
    }
}
