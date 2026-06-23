package com.childhelper.core.network.push

import com.childhelper.core.common.model.Alert
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped provider for the FCM alert flow.
 *
 * Owns the [MutableSharedFlow] that [FcmService] emits parsed alerts into
 * and that [ParentApp] (or any other consumer) collects from.
 *
 * Previously this flow was a static companion object on [FcmService], which
 * leaked across process lifecycles and could not be garbage collected.
 * Moving it to a Hilt @Singleton ensures proper scoping to the application
 * component lifecycle.
 */
@Singleton
class AlertFlowProvider @Inject constructor() {

    private val _alertFlow = MutableSharedFlow<Alert>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val alertFlow: SharedFlow<Alert> = _alertFlow.asSharedFlow()

    /**
     * Emit an alert parsed from an FCM push notification.
     * Called by [FcmService.onMessageReceived].
     */
    suspend fun emitAlert(alert: Alert) {
        _alertFlow.emit(alert)
    }

    /**
     * Internal test helper. Not for production use.
     */
    internal suspend fun emitTestAlert(alert: Alert) {
        _alertFlow.emit(alert)
    }
}
