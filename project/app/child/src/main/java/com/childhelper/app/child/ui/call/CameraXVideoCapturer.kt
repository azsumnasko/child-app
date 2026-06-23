package com.childhelper.app.child.ui.call

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraXVideoCapturer(
    private val context: Context,
    private val scope: CoroutineScope
) : LifecycleOwner {

    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    @Volatile private var isCapturing: Boolean = false
    @Volatile private var videoStarted: Boolean = false
    private var frameCount: Int = 0

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    val currentVideoTrack: VideoTrack? get() = videoTrack

    private fun log(msg: String) {
        Log.e("CameraXVC", msg)
    }

    suspend fun startCapture(
        factory: PeerConnectionFactory,
        eglBase: EglBase?,
        externalLo: LifecycleOwner?
    ): VideoTrack? = withContext(Dispatchers.Main) {
        stopCapture()

        // ALWAYS use internal lifecycle — external (monitoring service) lifecycle
        // goes to ON_PAUSE/ON_STOP when screen locks, unbinding the camera.
        lifecycleRegistry = LifecycleRegistry(this@CameraXVideoCapturer)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val lo: LifecycleOwner = this@CameraXVideoCapturer

        log("startCapture: using internal lifecycle")

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CameraXCapture",
            eglBase?.eglBaseContext
        )
        surfaceTextureHelper?.setTextureSize(640, 480)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val rotation = if (windowManager != null) {
            when (windowManager.defaultDisplay.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
        } else 0
        surfaceTextureHelper?.setFrameRotation(rotation)
        log("startCapture: frameRotation=$rotation")

        videoSource = factory.createVideoSource(false)

        frameCount = 0
        isCapturing = true

        surfaceTextureHelper?.startListening(object : VideoSink {
            override fun onFrame(frame: VideoFrame?) {
                frame?.let {
                    frameCount++
                    if (!videoStarted) {
                        videoStarted = true
                        videoSource?.capturerObserver?.onCapturerStarted(true)
                        log("capturerStarted signaled on first frame #$frameCount")
                    }
                    if (frameCount % 100 == 1) {
                        log("onFrame: #$frameCount rot=${it.rotation}")
                    }
                    videoSource?.capturerObserver?.onFrameCaptured(it)
                }
            }
        })
        isCapturing = true
        videoStarted = false

        val targetSize = android.util.Size(640, 480)
        preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
            .also { p ->
                p.setSurfaceProvider { request ->
                    val stHelper = surfaceTextureHelper
                    if (stHelper == null || !isCapturing) {
                        log("setSurfaceProvider: STH null or not capturing, deferring")
                        return@setSurfaceProvider
                    }
                    val surface = Surface(stHelper.surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
                        log("setSurfaceProvider: surface provided")
                    }
                }
            }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = try {
            suspendCancellableCoroutine { cont ->
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        if (cont.isActive) cont.resume(provider)
                    } catch (e: Exception) {
                        log("startCapture: providerFuture.get() FAILED: ${e.message}")
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (e: Exception) {
            log("startCapture: ProcessCameraProvider failed: ${e.message}")
            isCapturing = false
            stopCapture()
            return@withContext null
        }

        log("startCapture: cameraProvider acquired")

        val boundPreview = preview
        if (boundPreview == null) {
            log("startCapture: preview was null after setup")
            isCapturing = false
            stopCapture()
            return@withContext null
        }

        try {
            cameraProvider!!.bindToLifecycle(
                lo,
                currentCameraSelector,
                boundPreview
            )
            log("startCapture: Preview bound OK")
        } catch (e: Exception) {
            log("startCapture: bindToLifecycle FAILED: ${e.javaClass.simpleName}: ${e.message}")
            isCapturing = false
            stopCapture()
            return@withContext null
        }

        videoTrack = factory.createVideoTrack("video0", videoSource)
        videoTrack!!.setEnabled(true)

        log("startCapture: videoTrack created, returning")
        return@withContext videoTrack
    }

    fun stopCapture() {
        isCapturing = false
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            cameraProvider?.unbind(preview)
            preview = null

            videoTrack?.dispose()
            videoTrack = null

            surfaceTextureHelper?.stopListening()
            videoSource?.capturerObserver?.onCapturerStopped()
            videoSource?.dispose()
            videoSource = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            cameraProvider = null

            if (frameCount == 0) {
                log("stopCapture: WARNING - 0 frames received during this session")
            } else {
                log("stopCapture: total frames=$frameCount")
            }
        } catch (_: Exception) {
        }
    }

    fun switchCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val provider = cameraProvider ?: return
        val boundPreview = preview ?: return
        val lo: LifecycleOwner = this@CameraXVideoCapturer
        try {
            provider.unbind(boundPreview)
            provider.bindToLifecycle(lo, currentCameraSelector, boundPreview)
            log("switchCamera: switched to $currentCameraSelector")
        } catch (e: Exception) {
            log("switchCamera: FAILED: ${e.message}")
        }
    }
}
