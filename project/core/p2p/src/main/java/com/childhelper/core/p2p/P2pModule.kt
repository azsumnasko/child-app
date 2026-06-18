package com.childhelper.core.p2p

import android.content.Context
import com.childhelper.core.common.notification.NotificationSender
import com.childhelper.core.security.PairingCrypto
import com.childhelper.core.network.api.SignalingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object P2pModule {

    @Provides
    @Singleton
    fun provideLocalP2pManager(
        @ApplicationContext context: Context
    ): LocalP2pManager = LocalP2pManager(context)

    @Provides
    @Singleton
    fun provideQrPairingManager(
        pairingCrypto: PairingCrypto
    ): QrPairingManager = QrPairingManager(pairingCrypto)

    @Provides
    @Singleton
    fun provideP2pSignalingClient(
        localP2pManager: LocalP2pManager
    ): P2pSignalingClient = P2pSignalingClient(
        localP2pManager,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    @Provides
    @Singleton
    fun provideP2pAlertDispatcher(
        localP2pManager: LocalP2pManager
    ): P2pAlertDispatcher = P2pAlertDispatcher(
        localP2pManager,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    @Provides
    @Singleton
    fun provideHybridNotificationSender(
        p2pDispatcher: P2pAlertDispatcher,
        signalingApi: SignalingApi
    ): NotificationSender = HybridNotificationSender(p2pDispatcher, signalingApi)
}
