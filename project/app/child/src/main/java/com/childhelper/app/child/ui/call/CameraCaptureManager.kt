package com.childhelper.app.child.ui.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Manages local camera capture for WebRTC video calls.
 *
 * Responsible for:
 * - Enumerating available cameras via [Camera2Enumerator]
 * - Creating and managing a [CameraVideoCapturer]
 * - Initializing [SurfaceTextureHelper] for the capture thread
 * - Creating [VideoSource] and [VideoTrack]
 * - Starting and stopping camera capture with configurable resolution
 * - Switching between front and back cameras during a call
 * - Enabling/disabling the local video track
 * - Managing the [RtpSender] for the video track
 *
 * This class contains **only** camera-related WebRTC logic. It does not manage
 * peer connections, audio, or call state.
 *
 * @param context Android application context for camera enumeration.
 */
class CameraCaptureManager(
    private val context: Context
) {

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoRtpSender: RtpSender? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Return the current local [VideoTrack], or null if capture has not started.
     */
    val currentVideoTrack: VideoTrack? get() = localVideoTrack

    /**
     * Return the current [RtpSender] for the video track, or null.
     */
    val currentRtpSender: RtpSender? get() = videoRtpSender

    /**
     * Start local video capture and add the video track to the peer connection.
     *
     * Uses the front-facing camera by preference, falling back to any available camera.
     * If no camera is available, this method returns without throwing — callers should
     * check [currentVideoTrack] to determine if capture actually started.
     *
     * @param peerConnectionFactory The WebRTC factory used to create [VideoSource] and [VideoTrack].
     * @param eglBase The [EglBase] context for SurfaceTextureHelper.
     * @param peerConnection The peer connection to add the video track to. If null, the track
     *                       is created but not attached to any connection.
     */
    fun startCapture(
        peerConnectionFactory: PeerConnectionFactory,
        eglBase: EglBase?,
        peerConnection: org.webrtc.PeerConnection?
    ) {
        stopCapture()

        try {
            val cameraEnumerator = Camera2Enumerator(context)
            val deviceNames = cameraEnumerator.deviceNames

            val frontCamera = deviceNames.find { cameraEnumerator.isFrontFacing(it) }
                ?: deviceNames.firstOrNull()

            if (frontCamera != null) {
                val capturer = cameraEnumerator.createCapturer(frontCamera, null)
                    ?: throw IllegalStateException("Failed to create camera capturer")
                videoCapturer = capturer

                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    eglBase?.eglBaseContext
                )

                val videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                localVideoSource = videoSource
                capturer.initialize(
                    surfaceTextureHelper,
                    context,
                    videoSource.capturerObserver
                )
                capturer.startCapture(640, 480, 24)

                val videoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
                localVideoTrack = videoTrack
                videoTrack.setEnabled(true)

                videoRtpSender = peerConnection?.addTrack(videoTrack, listOf("stream0"))
            }
        } catch (e: Exception) {
            // Clean up on failure so we don't leak partially-initialized capturers
            stopCapture()
            throw CameraCaptureException("Failed to start camera capture", e)
        }
    }

    /**
     * Stop camera capture and release all associated resources.
     *
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    fun stopCapture() {
        handler.post {
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null

                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null

                localVideoTrack?.dispose()
                localVideoTrack = null

                localVideoSource?.dispose()
                localVideoSource = null

                videoRtpSender = null
            } catch (e: Exception) {
                // Best effort cleanup
            }
        }
    }

    /**
     * Switch between front and back camera during a call.
     *
     * No-op if capture is not active.
     */
    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    /**
     * Enable or disable the local video track.
     *
     * @param enabled `true` to enable video, `false` to disable.
     * @return `true` if the track state was changed, `false` if no track exists.
     */
    fun setVideoEnabled(enabled: Boolean): Boolean {
        val track = localVideoTrack ?: return false
        track.setEnabled(enabled)
        return true
    }

    /**
     * Convenience: set the [RtpSender] from outside (e.g. when the video track
     * is added to a peer connection by a coordinator rather than internally).
     *
     * @param sender The [RtpSender] for the local video track.
     */
    fun setRtpSender(sender: RtpSender?) {
        videoRtpSender = sender
    }

    /**
     * Reset the [RtpSender] without disposing other resources.
     * Call this when the peer connection is closed but camera capture should continue.
     */
    fun clearRtpSender() {
        videoRtpSender = null
    }

    /**
     * Exception thrown when camera capture fails to start.
     */
    class CameraCaptureException(message: String, cause: Throwable) : Exception(message, cause)
}
