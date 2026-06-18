package com.childhelper.core.network.model

import kotlinx.serialization.Serializable

/**
 * Request body for initiating a new pairing session from the child device.
 *
 * @param childDeviceId The unique identifier of the child device.
 * @param childPublicKey The X25519 or ECDH public key of the child device,
 *                       encoded as a Base64 string for transport.
 */
@Serializable
data class InitiatePairingRequest(
    val childDeviceId: String,
    val childPublicKey: String
)
