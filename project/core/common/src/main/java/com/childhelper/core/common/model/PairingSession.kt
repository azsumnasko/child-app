package com.childhelper.core.common.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle states of a device-pairing session.
 */
@Serializable
enum class PairingStatus {
    /** Pairing initiated; waiting for parent to enter the pairing code. */
    PENDING,

    /** Parent successfully entered code and exchanged keys; pairing is active. */
    COMPLETED,

    /** Pairing was explicitly revoked by either party. */
    REVOKED,

    /** Pairing code expired before completion (valid for 5 minutes). */
    EXPIRED
}

/**
 * Represents a pairing session between a child device and a parent device.
 *
 * Pairing uses a short-lived 6-character alphanumeric code displayed on the child
 * device that the parent enters to initiate a cryptographic key exchange. Once
 * completed, an ECDH shared secret is derived and all subsequent communication
 * is encrypted end-to-end.
 *
 * **Security:** Pairing codes expire after 5 minutes. Sessions can be revoked at
 * any time by either party, immediately invalidating the shared secret.
 *
 * @property sessionId Unique identifier for this pairing session (UUID).
 * @property pairingCode 6-character alphanumeric code displayed on child device.
 * @property childDeviceId The child device initiating pairing.
 * @property parentDeviceId The parent device that completed pairing (null until completed).
 * @property childPublicKey Base64-encoded ECDH public key of the child device (null until generated).
 * @property parentPublicKey Base64-encoded ECDH public key of the parent device (null until exchanged).
 * @property status Current lifecycle state of the pairing session.
 * @property createdAt Epoch millis when the session was created.
 * @property expiresAt Epoch millis when the pairing code expires (createdAt + 5 min).
 */
@Serializable
data class PairingSession(
    val sessionId: String,
    val pairingCode: String, // 6-character alphanumeric, expires in 5 min
    val childDeviceId: String,
    val parentDeviceId: String? = null,
    val childPublicKey: String? = null,
    val parentPublicKey: String? = null,
    val status: PairingStatus = PairingStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 5 * 60 * 1000
)
