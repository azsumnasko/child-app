package com.childhelper.core.network.model

import kotlinx.serialization.Serializable

/**
 * Request body for revoking an active pairing session.
 *
 * Either the child or parent device can request revocation. After revocation,
 * the shared secret is invalidated and the devices must re-pair.
 *
 * @param sessionId The pairing session ID to revoke.
 * @param deviceId The ID of the device requesting the revocation
 *                 (used for authorization audit logging).
 */
@Serializable
data class RevokePairingRequest(
    val sessionId: String,
    val deviceId: String
)
