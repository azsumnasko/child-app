package com.childhelper.core.network.api

import com.childhelper.core.network.model.InitiatePairingRequest
import com.childhelper.core.network.model.CompletePairingRequest
import com.childhelper.core.network.model.RevokePairingRequest
import com.childhelper.core.network.model.TurnCredentials
import com.childhelper.core.common.model.PairingSession
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit API interface for device pairing operations.
 *
 * Pairing establishes a trust relationship between the child device and parent device
 * using a short-lived pairing code exchanged out-of-band. After pairing, devices
 * derive a shared secret for encrypting subsequent communications.
 *
 * All pairing endpoints use HTTPS with certificate pinning (configured in [NetworkModule]).
 */
interface PairingApi {

    /**
     * Initiates a new pairing session from the child device.
     *
     * The child device generates a key pair and sends its public key along with
     * its device identifier. The server responds with a pairing session containing
     * a short-lived pairing code that the parent can use to complete pairing.
     *
     * @param request Contains the child device ID and public key for X3DH key agreement.
     * @return A [PairingSession] with the generated pairing code and session metadata.
     * @throws retrofit2.HttpException on 4xx/5xx errors.
     */
    @POST("/api/v1/pairing/initiate")
    suspend fun initiatePairing(
        @Body request: InitiatePairingRequest
    ): PairingSession

    /**
     * Completes an existing pairing session from the parent device.
     *
     * The parent device enters the pairing code displayed on the child device,
     * and provides its own public key. The server verifies the code and links
     * the two devices, enabling them to derive a shared secret.
     *
     * @param request Contains the session ID, parent device ID, and parent public key.
     * @return The updated [PairingSession] with status [com.childhelper.core.common.model.PairingStatus.COMPLETED].
     * @throws retrofit2.HttpException if the pairing code is invalid or expired.
     */
    @POST("/api/v1/pairing/complete")
    suspend fun completePairing(
        @Body request: CompletePairingRequest
    ): PairingSession

    /**
     * Completes pairing using only the 6-character code (no session ID needed).
     * The server finds the pending session by code.
     */
    @POST("/api/v1/pairing/complete-by-code")
    suspend fun completeByCode(
        @Body request: kotlinx.serialization.json.JsonObject
    ): PairingSession

    /**
     * Revokes an active pairing session, permanently unlinking the two devices.
     *
     * Either device can call this to terminate the pairing relationship.
     * After revocation, all shared secrets are invalidated and the devices
     * must re-pair to communicate.
     *
     * @param request Contains the session ID and the ID of the device requesting revocation.
     */
    @POST("/api/v1/pairing/revoke")
    suspend fun revokePairing(
        @Body request: RevokePairingRequest
    )

    /**
     * Retrieves the current status of a pairing session.
     *
     * Used by both devices to poll the pairing state, especially to detect
     * when the parent has completed the pairing process.
     *
     * @param sessionId The unique pairing session identifier.
     * @return The current [PairingSession] with its status and linked device info.
     */
    @GET("/api/v1/pairing/status/{sessionId}")
    suspend fun getPairingStatus(
        @Path("sessionId") sessionId: String
    ): PairingSession

    /**
     * Obtains temporary TURN server credentials for NAT traversal.
     *
     * TURN (Traversal Using Relays around NAT) servers relay media traffic
     * when a direct peer-to-peer connection cannot be established. Credentials
     * are time-limited and should be refreshed before each call.
     *
     * No media content ever passes through these servers — only relay metadata.
     *
     * @return [TurnCredentials] containing username, password, and TURN server URLs.
     */
    @POST("/api/v1/turn/credentials")
    suspend fun getTurnCredentials(): TurnCredentials

    /**
     * Registers this device's FCM token with the backend server.
     *
     * The server stores the mapping of deviceId → FCM token so it can
     * dispatch push notifications to the correct device.
     *
     * @param deviceId The unique identifier of this device.
     * @param fcmToken The FCM registration token obtained from Firebase.
     */
    @POST("/api/v1/register-token")
    suspend fun registerFcmToken(
        @Body body: kotlinx.serialization.json.JsonObject
    )

    @GET("/api/v1/pairing/parent-info/{parentDeviceId}")
    suspend fun getParentInfo(
        @Path("parentDeviceId") parentDeviceId: String
    ): kotlinx.serialization.json.JsonObject
}
