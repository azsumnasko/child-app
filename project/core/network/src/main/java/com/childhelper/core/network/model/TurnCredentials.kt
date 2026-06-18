package com.childhelper.core.network.model

import kotlinx.serialization.Serializable

/**
 * Time-limited TURN server credentials for NAT traversal relay.
 *
 * TURN servers are used only as a fallback when direct peer-to-peer
 * connectivity cannot be established. Media content is encrypted
 * end-to-end via DTLS-SRTP before traversing the relay — the TURN
 * server cannot inspect media payload.
 *
 * @param username The TURN username (typically time-scoped).
 * @param password The TURN authentication password.
 * @param urls List of TURN server URLs (e.g., "turn:turn.childhelper.com:3478").
 */
@Serializable
data class TurnCredentials(
    val username: String,
    val password: String,
    val urls: List<String>,
    val stunUrls: List<String> = listOf("stun:stun.l.google.com:19302")
)
