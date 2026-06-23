package com.childhelper.app.child.detection

import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.LifecycleOwner
import com.childhelper.core.common.model.DetectionConfig
import com.childhelper.core.common.model.MotionDetectionEvent
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
import java.util.UUID
import kotlin.math.abs

/**
 * Motion detector using frame differencing on CameraX ImageAnalysis frames.
 *
 * Pipeline:
 * 1. Receives camera frames from CameraPipeline via ImageAnalysis
 * 2. Downscales frames to 320x240 grayscale
 * 3. Computes pixel-wise difference between consecutive frames
 * 4. Applies threshold: 2+ consecutive frames with significant change trigger event
 * 5. Emits MotionDetectionEvent with metadata (confidence, consecutive count)
 *
 * Privacy: Camera frames are immediately discarded after analysis.
 * Only metadata (confidence, timestamp) is emitted — no image data leaves this class.
 *
 * @param cameraPipeline Source of camera frames
 * @param scope Coroutine scope for detection processing
 */
class MotionDetector(
    private val cameraPipeline: CameraPipeline,
    private val securePreferences: SecurePreferences,
    private val scope: CoroutineScope
) {

    private val _motionEvents = MutableSharedFlow<MotionDetectionEvent>()
    val motionEvents: Flow<MotionDetectionEvent> = _motionEvents.asSharedFlow()

    private var detectionJob: Job? = null
    @Volatile var isRunning: Boolean = false
        private set

    // Detection state
    private var config: DetectionConfig = DetectionConfig()
    private var previousFrame: ByteArray? = null
    private var consecutiveMotionFrames = 0
    private val stateMutex = Mutex()

    companion object {
        private const val TAG = "MotionDetector"

        // Analysis resolution — downscaled for performance
        private const val ANALYSIS_WIDTH = 320
        private const val ANALYSIS_HEIGHT = 240
        private const val FRAME_PIXEL_COUNT = ANALYSIS_WIDTH * ANALYSIS_HEIGHT

        // Default pixel difference threshold (0-255)
        private const val PIXEL_DIFF_THRESHOLD = 25

        // Default motion detection threshold (percentage of changed pixels)
        private const val DEFAULT_MOTION_THRESHOLD = 0.15f // 15% of pixels must change

        // Required consecutive frames with motion to trigger event
        private const val DEFAULT_CONSECUTIVE_FRAMES = 2
    }

    /**
     * Start motion detection with the given configuration.
     *
     * @param config Detection configuration including sensitivity and thresholds
     * @param lifecycleOwner The LifecycleOwner to bind the camera pipeline to
     */
    fun startDetection(config: DetectionConfig, lifecycleOwner: LifecycleOwner) {
        if (isRunning) return
        if (!cameraPipeline.hasCameraPermission()) {
            Log.w(TAG, "Cannot start motion detection: CAMERA permission not granted")
            return
        }

        this.config = config
        isRunning = true
        previousFrame = null
        consecutiveMotionFrames = 0

        // Start camera pipeline with the provided lifecycle owner
        cameraPipeline.startAnalysis(lifecycleOwner)

        // Begin processing camera frames
        detectionJob = scope.launch(Dispatchers.Default) {
            cameraPipeline.frames.collect { imageProxy ->
                if (!isRunning) {
                    cameraPipeline.safeClose(imageProxy)
                    return@collect
                }

                try {
                    processFrame(imageProxy)
                } finally {
                    // Always close the ImageProxy to prevent buffer exhaustion
                    cameraPipeline.safeClose(imageProxy)
                }
            }
        }

        // Listen for obstruction events
        scope.launch {
            cameraPipeline.obstructionEvents.collect {
                // Reset state when camera is obstructed
                stateMutex.withLock {
                    previousFrame = null
                    consecutiveMotionFrames = 0
                }
            }
        }
    }

    /**
     * Stop motion detection and clean up.
     */
    fun stopDetection() {
        isRunning = false
        detectionJob?.cancel()
        detectionJob = null
        cameraPipeline.stopAnalysis()
        previousFrame = null
        consecutiveMotionFrames = 0
    }

    /**
     * Process a single camera frame.
     *
     * @param imageProxy CameraX ImageProxy containing YUV data
     */
    private suspend fun processFrame(imageProxy: ImageProxy) {
        try {
            // Step 1: Convert ImageProxy to grayscale byte array (320x240)
            val currentFrame = cameraPipeline.imageProxyToGrayscale(
                imageProxy,
                ANALYSIS_WIDTH,
                ANALYSIS_HEIGHT
            ) ?: return

            // Step 2: Frame differencing
            val motionConfidence = stateMutex.withLock {
                val prev = previousFrame
                previousFrame = currentFrame

                if (prev == null) {
                    return@withLock 0f // First frame — no previous to compare
                }

                computeFrameDifference(prev, currentFrame)
            }

            // Step 3: Apply threshold logic
            stateMutex.withLock {
                val threshold = config.motionThreshold.takeIf { it > 0 } ?: DEFAULT_MOTION_THRESHOLD
                val requiredConsecutive = config.motionConsecutiveFrames.takeIf { it > 0 }
                    ?: DEFAULT_CONSECUTIVE_FRAMES

                if (motionConfidence >= threshold) {
                    consecutiveMotionFrames++

                    // Check if we've reached the threshold for event emission
                    if (consecutiveMotionFrames >= requiredConsecutive) {
                        emitMotionEvent(motionConfidence, consecutiveMotionFrames)
                        // Reset to allow re-detection after some time
                        consecutiveMotionFrames = 0
                    }
                } else {
                    // Reset on no motion
                    consecutiveMotionFrames = 0
                }
            }

            // Step 4: Previous frame is already replaced in the mutex lock
            // The old previous frame is garbage collected — no persistent storage

        } catch (e: Exception) {
            Log.e(TAG, "Error processing camera frame", e)
        }
    }

    /**
     * Compute the pixel-wise difference between two consecutive frames.
     *
     * @param prevFrame Previous grayscale frame
     * @param currFrame Current grayscale frame
     * @return Motion confidence score (0.0 - 1.0), representing the percentage
     *         of pixels that changed significantly
     */
    private fun computeFrameDifference(prevFrame: ByteArray, currFrame: ByteArray): Float {
        if (prevFrame.size != currFrame.size) return 0f

        var changedPixels = 0
        val pixelCount = prevFrame.size

        // Sample every 4th pixel for performance (still gives good coverage)
        val step = 4
        var i = 0
        while (i < pixelCount) {
            val prevVal = prevFrame[i].toInt() and 0xFF
            val currVal = currFrame[i].toInt() and 0xFF
            val diff = abs(currVal - prevVal)

            if (diff > PIXEL_DIFF_THRESHOLD) {
                changedPixels++
            }
            i += step
        }

        // Normalize by sampled pixel count
        val sampledPixels = pixelCount / step
        return changedPixels.toFloat() / sampledPixels
    }

    /**
     * Emit a motion detection event.
     *
     * @param confidence Motion confidence score (0.0 - 1.0)
     * @param consecutiveFrames Number of consecutive frames with motion
     */
    private suspend fun emitMotionEvent(confidence: Float, consecutiveFrames: Int) {
        val event = MotionDetectionEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            confidence = confidence,
            consecutiveFrames = consecutiveFrames,
            childDeviceId = getDeviceId()
        )

        _motionEvents.emit(event)
        Log.i(TAG, "Motion detected: confidence=$confidence, consecutive=$consecutiveFrames")
    }

    private suspend fun getDeviceId(): String {
        return securePreferences.getString("device_id", "child_device") ?: "child_device"
    }
}
