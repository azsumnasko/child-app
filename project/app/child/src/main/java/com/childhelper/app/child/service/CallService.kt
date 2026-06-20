package com.childhelper.app.child.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.childhelper.app.child.ChildApp
import com.childhelper.app.child.R
import com.childhelper.app.child.ui.call.CallManager
import com.childhelper.app.child.ui.call.CallState
import com.childhelper.core.common.model.CallSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for managing WebRTC calls.
 *
 * This service runs during an active call to ensure:
 * - The call continues even if the UI is dismissed
 * - Proper audio routing and wake lock management
 * - Persistent notification showing call state
 * - Proximity sensor handling (screen off when held to ear)
 *
 * Privacy:
 * - Uses FOREGROUND_SERVICE_TYPE_PHONE_CALL or MICROPHONE
 * - NO call audio is recorded or stored
 * - NO media files are created
 * - Call is peer-to-peer via WebRTC — no cloud media storage
 */
@AndroidEntryPoint
class CallService : Service() {

    @Inject
    lateinit var callManager: CallManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentCallSession: CallSession? = null

    private val binder = CallBinder()

    companion object {
        private const val TAG = "CallService"
        private const val NOTIFICATION_ID = 2001

        // Actions
        const val ACTION_START_CALL = "com.childhelper.app.child.START_CALL"
        const val ACTION_ACCEPT_CALL = "com.childhelper.app.child.ACCEPT_CALL"
        const val ACTION_END_CALL = "com.childhelper.app.child.END_CALL"
        const val ACTION_TOGGLE_MUTE = "com.childhelper.app.child.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.childhelper.app.child.TOGGLE_SPEAKER"

        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_HAS_VIDEO = "has_video"
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CallService created")

        if (!::callManager.isInitialized) {
            Log.w(TAG, "CallManager not yet injected — deferring WebRTC init")
            return
        }

        callManager.initializeWebRtc()

        serviceScope.launch {
            callManager.callState.collectLatest { state ->
                updateCallNotification(state)

                when (state) {
                    is CallState.Connected -> acquireWakeLock()
                    is CallState.Ended, is CallState.Error -> {
                        releaseWakeLock()
                        stopCallService()
                    }
                    else -> { /* no-op */ }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val contactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: ""
                val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""
                val hasVideo = intent.getBooleanExtra(EXTRA_HAS_VIDEO, true)
                startOutgoingCall(contactId, contactName, hasVideo)
            }
            ACTION_ACCEPT_CALL -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
                acceptIncomingCall(sessionId)
            }
            ACTION_END_CALL -> endCall()
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_TOGGLE_SPEAKER -> toggleSpeaker()
        }

        return START_NOT_STICKY
    }

    /**
     * Start an outgoing call.
     */
    private fun startOutgoingCall(contactId: String, contactName: String, hasVideo: Boolean) {
        if (!::callManager.isInitialized) {
            Log.e(TAG, "CallManager not injected — cannot start call")
            stopSelf()
            return
        }

        // Start as foreground service
        val notification = createCallNotification(
            state = "Calling $contactName...",
            isOngoing = false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        // Initiate the call
        callManager.initiateCall(contactId, hasVideo)
    }

    /**
     * Accept an incoming call.
     */
    private fun acceptIncomingCall(sessionId: String) {
        if (!::callManager.isInitialized) return

        // Update notification to connected state
        val notification = createCallNotification(
            state = "Call connected",
            isOngoing = true
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        callManager.acceptCall(sessionId)
    }

    /**
     * End the current call.
     */
    private fun endCall() {
        if (::callManager.isInitialized) callManager.endCall()
        releaseWakeLock()
        stopCallService()
    }

    private fun toggleMute() {
        // Mute toggle is handled by the CallManager/ViewModel
        // This action is for notification button only
    }

    private fun toggleSpeaker() {
        // Speaker toggle is handled by the CallManager/ViewModel
        // This action is for notification button only
    }

    /**
     * Update the foreground notification based on call state.
     */
    private fun updateCallNotification(state: CallState) {
        val stateText = when (state) {
            is CallState.Connecting -> "Connecting..."
            is CallState.Ringing -> "Ringing..."
            is CallState.Incoming -> "Incoming call"
            is CallState.Connected -> "Call in progress"
            is CallState.Ended -> "Call ended"
            is CallState.Error -> "Call error"
            CallState.Idle -> "Ready"
        }

        val isOngoing = state is CallState.Connected || state is CallState.Connecting ||
                state is CallState.Ringing

        val notification = createCallNotification(stateText, isOngoing)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Create a call notification.
     */
    private fun createCallNotification(state: String, isOngoing: Boolean): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // End call action
        val endCallIntent = Intent(this, CallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endCallPendingIntent = PendingIntent.getService(
            this,
            1,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, ChildApp.CHANNEL_CALL)
            .setContentTitle("ChildHelper Call")
            .setContentText(state)
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(pendingIntent)
            .setOngoing(isOngoing)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "End Call",
                    endCallPendingIntent
                ).build()
            )

        if (isOngoing) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    /**
     * Acquire a wake lock to keep the CPU active during the call.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChildHelper::CallWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10-minute timeout to prevent indefinite hold
        }
    }

    /**
     * Release the wake lock.
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock", e)
        }
    }

    /**
     * Stop the call service and clean up.
     */
    private fun stopCallService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CallService destroyed")
        releaseWakeLock()
        serviceScope.cancel()
    }

    /**
     * Binder for activities to communicate with the service.
     */
    inner class CallBinder : Binder() {
        fun getService(): CallService = this@CallService
        fun getCallManager(): CallManager = callManager
    }
}
