package com.childhelper.app.child.detection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Power mode enumeration for the camera pipeline.
 *
 * Represents the device's current battery-aware operating mode,
 * which determines camera resolution, frame rate, and whether
 * video analysis is active at all.
 */
enum class PowerMode {
    /** Normal operation: full resolution (640x480) at 15fps. */
    NORMAL,

    /** Low-power mode: reduced resolution (480x360) at 10fps. Active when battery < 20% and not charging. */
    LOW,

    /** Critical mode: camera disabled entirely. Active when battery < 10%. Audio-only detection continues. */
    CRITICAL
}

/**
 * Camera pipeline for real-time image analysis using CameraX.
 *
 * Privacy-first design:
 * - Uses CameraX ImageAnalysis (NOT MediaRecorder or video recording)
 * - Frames are processed in memory and immediately discarded
 * - NO video is ever written to files or uploaded to cloud
 * - NO persistent image storage on disk
 * - Includes camera obstruction detection (frame brightness check)
 *
 * Supports adaptive low-power mode that automatically reduces resolution
 * and frame rate based on battery level and charging state.
 *
 * Configuration:
 * - Normal: 640x480 at ~15fps
 * - Low-power: 480x360 at ~10fps (battery < 20%, not charging)
 * - Critical: camera disabled (battery < 10%)
 */
class CameraPipeline(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val _frames = MutableSharedFlow<ImageProxy>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: Flow<ImageProxy> = _frames.asSharedFlow()

    private val _obstructionEvents = MutableSharedFlow<Unit>()
    val obstructionEvents: Flow<Unit> = _obstructionEvents.asSharedFlow()

    private var isRunning = false
    private var frameCount = 0
    private var darkFrameCount = 0
    private val obstructionThreshold = 10 // Consecutive dark frames

    /**
     * Thread-safe closed-state tracker to prevent double-close of ImageProxy.
     * Uses WeakHashMap so entries are automatically cleaned up when ImageProxies
     * are garbage collected — no need to manually remove after closing.
     * CRIT-6 FIX: Was HashSet with manual remove in finally block, allowing
     * the same proxy to be closed again after removal.
     */
    private val closedFlags = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<ImageProxy, Boolean>()
    )

    private var currentLifecycleOwner: LifecycleOwner? = null
    /** Saved lifecycle owner for thermal recovery. See CRIT-1. */
    private var savedLifecycleOwner: LifecycleOwner? = null
    private var lowPowerJob: Job? = null

    /** Frame throttle counter for approximating target FPS in low-power modes. */
    private var frameSkipCounter = 0

    /**
     * Current power mode of the camera pipeline.
     * Reflects the latest emission from [lowPowerMode] flow.
     */
    private val _currentPowerMode = MutableStateFlow(PowerMode.NORMAL)
    val currentPowerMode: StateFlow<PowerMode> = _currentPowerMode.asStateFlow()

    /**
     * A [Flow] that emits the current [PowerMode] based on battery level and charging state.
     *
     * Emits:
     * - [PowerMode.CRITICAL] when battery < 10%
     * - [PowerMode.LOW] when battery < 20% and device is not charging
     * - [PowerMode.NORMAL] otherwise (battery >= 20%, or any level while charging)
     *
     * Collect this flow in a service to react to power state changes:
     * ```
     * serviceScope.launch {
     *     cameraPipeline.lowPowerMode.collect { mode ->
     *         // CameraPipeline handles rebind automatically
     *     }
     * }
     * ```
     */
    val lowPowerMode: Flow<PowerMode> = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val mode = when {
                    batteryPct < CRITICAL_BATTERY_THRESHOLD -> PowerMode.CRITICAL
                    batteryPct < LOW_BATTERY_THRESHOLD && !isCharging -> PowerMode.LOW
                    else -> PowerMode.NORMAL
                }
                trySend(mode)
            }
        }

        try {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            android.util.Log.w("CameraPipeline", "Cannot register battery receiver, assuming normal power", e)
            trySend(PowerMode.NORMAL)
            close(e)
            return@callbackFlow
        }

        // Emit initial state
        val initialIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        initialIntent?.let { receiver.onReceive(context, it) } ?: trySend(PowerMode.NORMAL)

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }
        }
    }.distinctUntilChanged()

    companion object {
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480
        const val OBSTRUCTION_BRIGHTNESS_THRESHOLD = 15 // Average pixel value threshold (0-255)

        private const val TAG = "CameraPipeline"

        // Low-power mode thresholds
        private const val LOW_BATTERY_THRESHOLD = 20 // percent
        private const val CRITICAL_BATTERY_THRESHOLD = 10 // percent

        // Resolution configurations per power mode
        private val RESOLUTION_NORMAL = android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT)   // 640x480
        private val RESOLUTION_LOW_POWER = android.util.Size(480, 360)                       // 480x360

        // Target frame rates (approximated via frame skipping)
        private const val FPS_NORMAL = 15
        private const val FPS_LOW_POWER = 10

        // Frame skip divisors to approximate target FPS:
        // At ~30fps camera source, skip every Nth frame to get target rate.
        private const val SKIP_DIVISOR_NORMAL = 2     // ~15fps effective
        private const val SKIP_DIVISOR_LOW_POWER = 3  // ~10fps effective
    }

    /**
     * Start camera analysis.
     * Requires CAMERA permission and a valid LifecycleOwner.
     *
     * Automatically subscribes to [lowPowerMode] and adjusts camera resolution
     * and frame rate based on battery state. When battery drops below critical
     * threshold, the camera is unbound entirely (audio-only detection continues).
     *
     * @param lifecycleOwner The LifecycleOwner to bind the camera to.
     *        Pass the Activity/Fragment from UI contexts, or the Service
     *        (which must implement LifecycleOwner) when running in background.
     */
    fun startAnalysis(lifecycleOwner: LifecycleOwner) {
        if (isRunning) return
        if (!hasCameraPermission()) {
            android.util.Log.w("CameraPipeline", "Camera permission denied — motion detection disabled")
            return
        }

        isRunning = true
        currentLifecycleOwner = lifecycleOwner

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraAnalysis(lifecycleOwner)
            } catch (e: Exception) {
                isRunning = false
                currentLifecycleOwner = null
            }
        }, ContextCompat.getMainExecutor(context))

        // Subscribe to low-power mode changes and rebind camera accordingly
        lowPowerJob = scope.launch {
            lowPowerMode.collect { mode ->
                if (!isRunning) return@collect
                _currentPowerMode.value = mode

                when (mode) {
                    PowerMode.CRITICAL -> {
                        Log.i(TAG, "Critical power mode: unbinding camera, audio-only detection")
                        unbindCamera()
                    }
                    PowerMode.LOW -> {
                        Log.i(TAG, "Low power mode: reducing camera resolution to 480x360 @ 10fps")
                        rebindWithPowerMode(lifecycleOwner, mode)
                    }
                    PowerMode.NORMAL -> {
                        Log.i(TAG, "Normal power mode: full camera resolution 640x480 @ 15fps")
                        rebindWithPowerMode(lifecycleOwner, mode)
                    }
                }
            }
        }
    }

    private fun bindCameraAnalysis(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return

        // Unbind any existing use cases
        provider.unbindAll()

        // Configure image analysis
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isRunning) {
                safeClose(imageProxy)
                return@setAnalyzer
            }

            // Frame-rate throttling for low-power modes
            val skipDivisor = when (_currentPowerMode.value) {
                PowerMode.NORMAL -> SKIP_DIVISOR_NORMAL
                PowerMode.LOW -> SKIP_DIVISOR_LOW_POWER
                PowerMode.CRITICAL -> {
                    safeClose(imageProxy)
                    return@setAnalyzer
                }
            }
            frameSkipCounter++
            if (frameSkipCounter % skipDivisor != 0) {
                safeClose(imageProxy)
                return@setAnalyzer
            }

            // Check for camera obstruction
            checkObstruction(imageProxy)

            // Emit the frame for downstream processing
            scope.launch {
                try {
                    _frames.emit(imageProxy)
                } catch (e: Exception) {
                    // If emit fails, ensure the image is closed
                    safeClose(imageProxy)
                }
            }
        }

        // Use back camera for room monitoring
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Bind to the provided LifecycleOwner — works from both Activity and Service
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
        } catch (e: Exception) {
            isRunning = false
        }
    }

    /**
     * Rebind the camera analysis use case with settings appropriate for the
     * current [PowerMode].
     *
     * @param lifecycleOwner The LifecycleOwner to bind to.
     * @param mode The power mode to configure for.
     */
    private fun rebindWithPowerMode(lifecycleOwner: LifecycleOwner, mode: PowerMode) {
        val provider = cameraProvider ?: return

        // Determine resolution based on power mode
        val targetSize = when (mode) {
            PowerMode.LOW -> RESOLUTION_LOW_POWER
            else -> RESOLUTION_NORMAL
        }

        // Unbind existing analysis
        provider.unbind(imageAnalysis)
        imageAnalysis?.clearAnalyzer()

        // Create new ImageAnalysis with appropriate resolution
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            targetSize,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        frameSkipCounter = 0

        imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isRunning) {
                safeClose(imageProxy)
                return@setAnalyzer
            }

            // Frame-rate throttling for low-power modes
            val skipDivisor = when (_currentPowerMode.value) {
                PowerMode.NORMAL -> SKIP_DIVISOR_NORMAL
                PowerMode.LOW -> SKIP_DIVISOR_LOW_POWER
                PowerMode.CRITICAL -> {
                    safeClose(imageProxy)
                    return@setAnalyzer
                }
            }
            frameSkipCounter++
            if (frameSkipCounter % skipDivisor != 0) {
                safeClose(imageProxy)
                return@setAnalyzer
            }

            checkObstruction(imageProxy)

            scope.launch {
                try {
                    _frames.emit(imageProxy)
                } catch (e: Exception) {
                    safeClose(imageProxy)
                }
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rebind camera for power mode $mode", e)
        }
    }

    /**
     * Unbind the camera analysis use case while keeping the pipeline state.
     * Called when entering [PowerMode.CRITICAL] to disable video entirely.
     */
    private fun unbindCamera() {
        try {
            cameraProvider?.unbind(imageAnalysis)
            imageAnalysis?.clearAnalyzer()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera", e)
        }
    }

    /**
     * Resume normal camera operation after recovering from low-power or critical mode.
     *
     * Rebinds the camera with full resolution (640x480) at normal frame rate.
     * Call this when the device starts charging or battery recovers above thresholds.
     */
    fun resumeNormalMode() {
        // CRIT-1 FIX: Use savedLifecycleOwner as fallback when currentLifecycleOwner
        // was nulled by stopAnalysis() during thermal HOT state. Without this, the
        // camera can never recover after thermal throttling because resumeNormalMode()
        // returns early with no lifecycle owner to bind to.
        val lifecycleOwner = currentLifecycleOwner ?: savedLifecycleOwner ?: return
        if (!isRunning) return

        _currentPowerMode.value = PowerMode.NORMAL
        Log.i(TAG, "Resuming normal camera mode")
        rebindWithPowerMode(lifecycleOwner, PowerMode.NORMAL)
    }

    /**
     * Stop camera analysis and release resources.
     * All pending frames are discarded.
     */
    fun stopAnalysis() {
        isRunning = false
        // CRIT-1 FIX: Save the lifecycle owner BEFORE nulling it so that
        // resumeNormalMode() can recover after thermal throttling.
        savedLifecycleOwner = currentLifecycleOwner
        currentLifecycleOwner = null
        frameSkipCounter = 0

        lowPowerJob?.cancel()
        lowPowerJob = null

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // Best effort cleanup
        }

        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider = null

        frameCount = 0
        darkFrameCount = 0
    }

    /**
     * Check if the camera view is obstructed by analyzing frame brightness.
     * If too many consecutive dark frames are detected, emits an obstruction event.
     */
    private fun checkObstruction(imageProxy: ImageProxy) {
        try {
            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val width = imageProxy.width
            val height = imageProxy.height

            // Sample pixels to estimate brightness (every 8th pixel for performance)
            var totalBrightness = 0L
            var sampleCount = 0

            for (y in 0 until height step 8) {
                for (x in 0 until width step 8) {
                    val pixel = yBuffer.get(y * yRowStride + x).toInt() and 0xFF
                    totalBrightness += pixel
                    sampleCount++
                }
            }

            val avgBrightness = if (sampleCount > 0) (totalBrightness / sampleCount).toInt() else 0

            if (avgBrightness < OBSTRUCTION_BRIGHTNESS_THRESHOLD) {
                darkFrameCount++
                if (darkFrameCount >= obstructionThreshold) {
                    scope.launch {
                        _obstructionEvents.emit(Unit)
                    }
                    darkFrameCount = 0 // Reset after reporting
                }
            } else {
                darkFrameCount = maxOf(0, darkFrameCount - 1) // Gradual recovery
            }

            frameCount++
        } catch (e: Exception) {
            // Best effort — don't crash the pipeline
        }
    }

    /**
     * Convert a YUV ImageProxy to grayscale byte array.
     * Used by downstream motion detection.
     *
     * @param imageProxy The CameraX ImageProxy
     * @param targetWidth Target width for the output (will be downscaled)
     * @param targetHeight Target height for the output
     * @return Grayscale byte array, or null if conversion fails
     */
    fun imageProxyToGrayscale(
        imageProxy: ImageProxy,
        targetWidth: Int = 320,
        targetHeight: Int = 240
    ): ByteArray? {
        return try {
            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val width = imageProxy.width
            val height = imageProxy.height

            // Fast path: if target matches source, just read Y plane
            if (targetWidth == width && targetHeight == height) {
                val result = ByteArray(width * height)
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(result, row * width, width)
                }
                return result
            }

            // Downscale using simple nearest-neighbor sampling
            val result = ByteArray(targetWidth * targetHeight)
            val xScale = width.toFloat() / targetWidth
            val yScale = height.toFloat() / targetHeight

            for (y in 0 until targetHeight) {
                val srcY = (y * yScale).toInt()
                val srcRowOffset = srcY * yRowStride
                val dstRowOffset = y * targetWidth

                for (x in 0 until targetWidth) {
                    val srcX = (x * xScale).toInt()
                    result[dstRowOffset + x] = yBuffer.get(srcRowOffset + srcX)
                }
            }

            result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if the app has CAMERA permission.
     */
    fun hasCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Release all resources. Call when the pipeline is no longer needed.
     */
    fun release() {
        stopAnalysis()
        cameraExecutor.shutdown()
    }

    /**
     * Safely close an [ImageProxy] if it has not already been closed.
     * This prevents double-close crashes when both CameraPipeline and
     * downstream consumers attempt to close the same ImageProxy.
     *
     * CRIT-6 FIX: Previously removed the proxy from closedFlags in finally{},
     * which allowed the same object reference to be closed again after the
     * removal. Now uses WeakHashMap-backed set — entries auto-expire when
     * the ImageProxy is garbage collected, so we never remove eagerly.
     *
     * @param imageProxy The ImageProxy to close.
     * @return `true` if this call actually closed the ImageProxy.
     */
    fun safeClose(imageProxy: ImageProxy): Boolean {
        return if (closedFlags.add(imageProxy)) {
            try {
                imageProxy.close()
                true
            } catch (e: Exception) {
                // Already closed or illegal state — ignore
                false
            }
            // Intentionally NOT removing from closedFlags — the WeakHashMap
            // will clean up automatically when the ImageProxy is GC'd.
        } else {
            false
        }
    }
}
