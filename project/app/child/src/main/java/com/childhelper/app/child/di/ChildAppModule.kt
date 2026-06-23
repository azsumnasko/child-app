package com.childhelper.app.child.di

import android.content.Context
import com.childhelper.app.child.detection.AudioPipeline
import com.childhelper.app.child.detection.CameraPipeline
import com.childhelper.app.child.detection.CryDetector
import com.childhelper.app.child.detection.EventPipeline
import com.childhelper.app.child.detection.MotionDetector
import com.childhelper.app.child.detection.TfliteRunner
import com.childhelper.app.child.service.MonitoringCoordinator
import com.childhelper.app.child.ui.bedtime.VoicePromptManager
import com.childhelper.app.child.ui.call.AudioDeviceManager
import com.childhelper.app.child.ui.call.CallManager
import com.childhelper.app.child.ui.call.CameraCaptureManager
import com.childhelper.app.child.ui.call.CameraXVideoCapturer
import com.childhelper.app.child.ui.call.WebRtcPeerConnectionManager
import com.childhelper.app.child.ui.sos.SosManager
import com.childhelper.core.common.notification.NotificationSender
import com.childhelper.core.network.api.PairingApi
import com.childhelper.core.network.signaling.WebRtcSignalingClient
import com.childhelper.core.security.SecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChildScope

@Module
@InstallIn(SingletonComponent::class)
object ChildAppModule {

    @Provides
    @Singleton
    @ChildScope
    fun provideChildCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideTfliteRunner(
        @ApplicationContext context: Context
    ): TfliteRunner {
        return TfliteRunner(context)
    }

    @Provides
    @Singleton
    fun provideAudioPipeline(
        @ApplicationContext context: Context,
        @ChildScope scope: CoroutineScope
    ): AudioPipeline {
        return AudioPipeline(context, scope)
    }

    @Provides
    @Singleton
    fun provideCameraPipeline(
        @ApplicationContext context: Context,
        @ChildScope scope: CoroutineScope
    ): CameraPipeline {
        return CameraPipeline(context, scope)
    }

    @Provides
    @Singleton
    fun provideThermalMonitor(
        @ApplicationContext context: Context,
        @ChildScope scope: CoroutineScope
    ): com.childhelper.app.child.service.ThermalMonitor {
        return com.childhelper.app.child.service.ThermalMonitor(context, scope)
    }

    @Provides
    @Singleton
    fun provideCryDetector(
        audioPipeline: AudioPipeline,
        tfliteRunner: TfliteRunner,
        @ChildScope scope: CoroutineScope,
        securePreferences: SecurePreferences
    ): CryDetector {
        return CryDetector(audioPipeline, tfliteRunner, scope, securePreferences)
    }

    @Provides
    @Singleton
    fun provideMotionDetector(
        cameraPipeline: CameraPipeline,
        securePreferences: SecurePreferences,
        @ChildScope scope: CoroutineScope
    ): MotionDetector {
        return MotionDetector(cameraPipeline, securePreferences, scope)
    }

    @Provides
    @Singleton
    fun provideEventPipeline(
        @ApplicationContext context: Context,
        securePreferences: SecurePreferences,
        @ChildScope scope: CoroutineScope,
        notificationSender: NotificationSender
    ): EventPipeline {
        return EventPipeline(context, securePreferences, scope, notificationSender)
    }

    @Provides
    @Singleton
    fun provideSosManager(
        @ApplicationContext context: Context,
        eventPipeline: EventPipeline,
        @ChildScope scope: CoroutineScope
    ): SosManager {
        return SosManager(context, eventPipeline, scope)
    }

    @Provides
    @Singleton
    fun provideVoicePromptManager(
        @ApplicationContext context: Context
    ): VoicePromptManager {
        return VoicePromptManager(context)
    }

    @Provides
    @Singleton
    fun provideWebRtcPeerConnectionManager(
        @ChildScope scope: CoroutineScope
    ): WebRtcPeerConnectionManager {
        return WebRtcPeerConnectionManager(scope)
    }

    @Provides
    @Singleton
    fun provideCameraCaptureManager(
        @ApplicationContext context: Context
    ): CameraCaptureManager {
        return CameraCaptureManager(context)
    }

    @Provides
    @Singleton
    fun provideCameraXVideoCapturer(
        @ApplicationContext context: Context,
        @ChildScope scope: CoroutineScope
    ): CameraXVideoCapturer {
        return CameraXVideoCapturer(context, scope)
    }

    @Provides
    @Singleton
    fun provideAudioDeviceManager(
        @ApplicationContext context: Context
    ): AudioDeviceManager {
        return AudioDeviceManager(context)
    }

    @Provides
    @Singleton
    fun provideCallManager(
        @ApplicationContext context: Context,
        signalingClient: WebRtcSignalingClient,
        securePreferences: SecurePreferences,
        @ChildScope scope: CoroutineScope,
        peerConnectionManager: WebRtcPeerConnectionManager,
        cameraCaptureManager: CameraCaptureManager,
        audioDeviceManager: AudioDeviceManager,
        pairingApi: PairingApi,
        monitoringCoordinator: MonitoringCoordinator,
        cameraPipeline: CameraPipeline,
        cameraXVideoCapturer: CameraXVideoCapturer
    ): CallManager {
        return CallManager(
            context,
            signalingClient,
            securePreferences,
            scope,
            peerConnectionManager,
            cameraCaptureManager,
            audioDeviceManager,
            pairingApi,
            monitoringCoordinator,
            cameraPipeline,
            cameraXVideoCapturer
        )
    }

    @Provides
    @Singleton
    fun provideMonitoringCoordinator(
        cryDetector: CryDetector,
        motionDetector: MotionDetector,
        cameraPipeline: CameraPipeline,
        eventPipeline: EventPipeline,
        @ChildScope scope: CoroutineScope
    ): MonitoringCoordinator {
        return MonitoringCoordinator(
            cryDetector,
            motionDetector,
            cameraPipeline,
            eventPipeline,
            scope
        )
    }

}
