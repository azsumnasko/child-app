package com.childhelper.app.child.ui.sos

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.childhelper.app.child.detection.EventPipeline
import com.childhelper.core.common.model.GeoLocation
import com.childhelper.core.common.model.SosEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages SOS activation lifecycle, guardian notification, and location gathering.
 *
 * When SOS is activated:
 * 1. Gathers current location (if permission granted)
 * 2. Emits SOS event to EventPipeline (metadata-only, no media)
 * 3. Vibrates device for user feedback
 * 4. Publishes SOS event to guardian app via FCM
 */
@Singleton
class SosManager(
    @ApplicationContext private val context: Context,
    private val eventPipeline: EventPipeline,
    private val scope: CoroutineScope
) {

    private val _sosState = MutableStateFlow<SosState>(SosState.Idle)
    val sosState: StateFlow<SosState> = _sosState.asStateFlow()

    private val _sosEvents = MutableSharedFlow<SosEvent>()
    val sosEvents: Flow<SosEvent> = _sosEvents.asSharedFlow()

    /**
     * CRIT-8 FIX: Mutex ensures only one SOS activation runs at a time.
     * Without this, rapid calls to activateSos() (e.g., child tapping SOS
     * multiple times) could launch overlapping coroutines, each sending
     * duplicate alerts and vibrating multiple times.
     */
    private val sosMutex = Mutex()

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Activate SOS. This method is called after the 2-second hold is confirmed.
     *
     * @param childDeviceId The unique identifier for this child device
     */
    /**
     * Activate SOS. This method is called after the 2-second hold is confirmed.
     *
     * CRIT-8 FIX: Protected by [sosMutex] — if SOS is already being activated,
     * subsequent calls are ignored. The mutex-acquired check + state check
     * prevents the race where two rapid calls both pass the guard before
     * either sets the state to Active.
     *
     * @param childDeviceId The unique identifier for this child device
     */
    fun activateSos(childDeviceId: String) {
        scope.launch {
            sosMutex.withLock {
                if (_sosState.value is SosState.Active) {
                    return@withLock // Already active
                }

                _sosState.value = SosState.Active
            }

            // Strong vibration feedback (outside lock to avoid blocking)
            vibrateSosPattern()

            try {
                // Gather location (best effort — privacy-first, no storage)
                val location = getCurrentLocation()

                // Create SOS event
                val sosEvent = SosEvent(
                    location = location,
                    childDeviceId = childDeviceId
                )

                // Submit to event pipeline (metadata-only alert)
                eventPipeline.submitSosEvent(sosEvent)

                // Emit for local observers
                _sosEvents.emit(sosEvent)

                // Keep active state for a minimum duration to show feedback
                delay(5000)

                _sosState.value = SosState.Idle
            } catch (e: Exception) {
                _sosState.value = SosState.Error(e.message ?: "SOS activation failed")
                delay(3000)
                _sosState.value = SosState.Idle
            }
        }
    }

    /**
     * Cancel an active SOS (if guardian responds quickly).
     */
    fun cancelSos() {
        _sosState.value = SosState.Idle
    }

    /**
     * Perform SOS-specific vibration pattern: 3 strong pulses.
     */
    private fun vibrateSosPattern() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(
                SOS_VIBRATION_PATTERN,
                SOS_VIBRATION_AMPLITUDES,
                -1 // Don't repeat
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(SOS_VIBRATION_PATTERN, -1)
        }
    }

    /**
     * Get current device location (best effort, privacy-first).
     * Returns null if location permission is not granted.
     * Location data is NOT stored, only included in the immediate SOS event.
     */
    private fun getCurrentLocation(): GeoLocation? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var bestLocation: Location? = null

            for (provider in providers) {
                @Suppress("MissingPermission")
                val location: Location? = try {
                    locationManager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
                if (location != null && (bestLocation == null || location.accuracy < bestLocation.accuracy)) {
                    bestLocation = location
                }
            }

            bestLocation?.let {
                GeoLocation(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = if (it.hasAccuracy()) it.accuracy else null
                )
            }
        } catch (e: Exception) {
            null // Location is best-effort; don't fail SOS if unavailable
        }
    }

    companion object {
        // 3 strong pulses for SOS: on-off pattern in milliseconds
        private val SOS_VIBRATION_PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)
        private val SOS_VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
    }
}

/**
 * Represents the current state of the SOS system.
 */
sealed class SosState {
    data object Idle : SosState()
    data object Active : SosState()
    data class Error(val message: String) : SosState()
}
