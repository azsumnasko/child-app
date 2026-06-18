package com.childhelper.core.network.model

import kotlinx.serialization.Serializable

/**
 * Request body for completing a pairing session from the parent device.
 *
 * The parent enters the pairing code displayed on the child device and
 * provides its own public key. The server verifies the code and establishes
 * the trust relationship between the two devices.
 *
 * @param sessionId The pairing session ID returned from [initiatePairing][com.childhelper.core.network.api.PairingApi.initiatePairing].
 * @param parentDeviceId The unique identifier of the parent device.
 * @param parentPublicKey The X25519 or ECDH public key of the parent device,
 *                        encoded as a Base64 string for transport.
 */
@Serializable
data class CompletePairingRequest(
    val sessionId: String,
    val parentDeviceId: String,
    val parentPublicKey: String
)
