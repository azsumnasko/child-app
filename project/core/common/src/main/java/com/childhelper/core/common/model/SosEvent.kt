package com.childhelper.core.common.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Geographic location included in an SOS event.
 *
 * Location sharing is opt-in and controlled by [AppSettings.locationSharingEnabled].
 * When disabled, SOS events are sent without location data.
 *
 * @property latitude GPS latitude in decimal degrees.
 * @property longitude GPS longitude in decimal degrees.
 * @property accuracy Optional horizontal accuracy radius in meters.
 */
@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null
)

/**
 * Represents an SOS activation event triggered by the child.
 *
 * The child presses and holds the SOS button to immediately alert all paired
 * parent devices. The event includes an optional location if location sharing
 * is enabled in settings.
 *
 * **Privacy:** No audio or video is captured during SOS. Only metadata (timestamp
 * and optional location) is transmitted. Location sharing is disabled by default.
 *
 * @property id Unique identifier for this SOS event (UUID).
 * @property timestamp Epoch millis when the SOS was activated.
 * @property location Optional GPS location (only if location sharing is enabled).
 * @property childDeviceId The device ID of the child device that triggered SOS.
 */
@Serializable
data class SosEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val location: GeoLocation? = null,
    val childDeviceId: String
)
