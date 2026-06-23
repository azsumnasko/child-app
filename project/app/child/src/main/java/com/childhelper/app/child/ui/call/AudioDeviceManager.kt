package com.childhelper.app.child.ui.call

import android.content.Context
import android.media.AudioManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

/**
 * Manages local audio capture and audio device routing for WebRTC calls.
 *
 * Responsible for:
 * - Creating [AudioSource] and [AudioTrack] via WebRTC
 * - Enabling/disabling the local audio track (mute/unmute)
 * - Managing the Android [AudioManager] for call-optimized audio routing
 * - Switching between earpiece and speakerphone
 * - Enabling/disabling talk-back (half-duplex voice communication)
 *
 * This class contains **only** audio-related logic. It does not manage
 * peer connections, video, or call state.
 *
 * @param context Android application context for [AudioManager] access.
 */
class AudioDeviceManager(
    private val context: Context
) {

    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Previous [AudioManager] mode saved before entering a call so it can be restored.
     */
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    /**
     * Previous speakerphone state saved so it can be restored after the call.
     */
    private var wasSpeakerphoneOn: Boolean = false

    /**
     * Return the current local [AudioTrack], or null if audio has not started.
     */
    val currentAudioTrack: AudioTrack? get() = localAudioTrack

    /**
     * Start local audio capture and add the audio track to the peer connection.
     *
     * Configures echo cancellation, noise suppression, and auto gain control.
     * Also sets the [AudioManager] to [AudioManager.MODE_IN_COMMUNICATION] for
     * optimal VoIP routing.
     *
     * @param peerConnectionFactory The WebRTC factory used to create [AudioSource] and [AudioTrack].
     * @param peerConnection The peer connection to add the audio track to. If null, the track
     *                       is created but not attached to any connection.
     */
    fun startAudioCapture(
        peerConnectionFactory: PeerConnectionFactory,
        peerConnection: org.webrtc.PeerConnection?
    ) {
        stopAudioCapture()

        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioSource = audioSource

        val audioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
            ?: throw IllegalStateException("Failed to create audio track")
        localAudioTrack = audioTrack
        audioTrack.setEnabled(true)

        val sender = peerConnection?.addTrack(audioTrack, listOf("stream0"))
        android.util.Log.e("AudioDM", "addTrack returned sender=${sender != null}")
        if (sender == null) {
            android.util.Log.e("AudioDM", "WARNING: addTrack returned null — audio NOT sent!")
        }

        // Configure AudioManager for VoIP
        configureAudioManagerForCall()
    }

    /**
     * Stop local audio capture and release all associated resources.
     *
     * Restores the previous [AudioManager] mode and speakerphone state.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    fun stopAudioCapture() {
        try {
            localAudioTrack?.dispose()
            localAudioTrack = null

            localAudioSource?.dispose()
            localAudioSource = null

            restoreAudioManager()
        } catch (e: Exception) {
            // Best effort cleanup
        }
    }

    /**
     * Enable or disable the local audio track.
     *
     * @param enabled `true` to enable audio, `false` to mute.
     * @return `true` if the track state was changed, `false` if no track exists.
     */
    fun setAudioEnabled(enabled: Boolean): Boolean {
        val track = localAudioTrack ?: return false
        track.setEnabled(enabled)
        return true
    }

    /**
     * Check whether the local audio track is currently enabled (unmuted).
     *
     * @return `true` if audio is enabled, `false` if muted or no track exists.
     */
    fun isAudioEnabled(): Boolean = localAudioTrack?.enabled() ?: false

    /**
     * Enable or disable talk-back (half-duplex voice communication).
     *
     * Talk-back allows a guardian to speak to the child. When enabled, the local
     * microphone is activated so the child can hear the guardian. When disabled,
     * the microphone is muted.
     *
     * @param enabled `true` to enable the microphone for talk-back, `false` to disable.
     * @return `true` if the state was changed, `false` if no audio track exists.
     */
    fun enableTalkBack(enabled: Boolean): Boolean {
        return setAudioEnabled(enabled)
    }

    /**
     * Toggle speakerphone on or off.
     *
     * @param enabled `true` to route audio through the speakerphone,
     *                `false` to route through the earpiece.
     */
    fun setSpeakerphoneEnabled(enabled: Boolean) {
        audioManager?.isSpeakerphoneOn = enabled
    }

    fun isSpeakerphoneOn(): Boolean = audioManager?.isSpeakerphoneOn ?: false

    private fun configureAudioManagerForCall() {
        val am = audioManager ?: return
        try {
            previousAudioMode = am.mode
            wasSpeakerphoneOn = am.isSpeakerphoneOn

            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: SecurityException) {
            android.util.Log.w("AudioDeviceManager", "Could not configure AudioManager", e)
        }
    }

    private fun restoreAudioManager() {
        val am = audioManager ?: return
        try {
            am.isSpeakerphoneOn = wasSpeakerphoneOn
            am.mode = previousAudioMode
        } catch (e: SecurityException) {
            android.util.Log.w("AudioDeviceManager", "Could not restore AudioManager", e)
        }
    }
}
