package com.childhelper.core.network.api

import com.childhelper.core.network.signaling.IceMessage
import com.childhelper.core.network.signaling.SignalingMessage
import com.childhelper.core.network.signaling.SdpMessage
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit API interface for WebRTC signaling message exchange.
 *
 * Signaling is the process by which two peers exchange control messages
 * (SDP offers/answers and ICE candidates) to establish a peer-to-peer
 * WebRTC connection. **No media data flows through these endpoints** —
 * only lightweight control metadata.
 *
 * After the peer connection is established, all audio/video media travels
 * directly between devices (or via TURN relay) without touching the server.
 */
interface SignalingApi {

    /**
     * Sends an SDP offer from the caller to the callee.
     *
     * The offer describes the caller's media capabilities and transport parameters.
     * The callee should respond with an answer via [sendAnswer].
     *
     * @param offer The SDP offer message containing the session description.
     */
    @POST("/api/v1/signal/offer")
    suspend fun sendOffer(
        @Body offer: SignalingMessage
    )

    /**
     * Sends an SDP answer from the callee back to the caller.
     *
     * The answer accepts the offer and describes the callee's media parameters,
     * completing the SDP handshake. After this exchange, both peers begin
     * sending ICE candidates.
     *
     * @param answer The SDP answer message responding to the caller's offer.
     */
    @POST("/api/v1/signal/answer")
    suspend fun sendAnswer(
        @Body answer: SignalingMessage
    )

    /**
     * Sends an ICE candidate to the peer device.
     *
     * ICE candidates contain network address information used to find
     * a viable communication path between peers. Multiple candidates
     * may be sent for each media stream until a working path is found.
     *
     * @param candidate The ICE candidate with network address and SDP line info.
     */
    @POST("/api/v1/signal/ice")
    suspend fun sendIceCandidate(
        @Body candidate: IceMessage
    )

    /**
     * Polls for pending signaling messages addressed to this device.
     *
     * The device periodically polls this endpoint to receive incoming
     * offers, answers, and ICE candidates from the peer. In production,
     * this can be replaced with push-triggered polling for lower latency.
     *
     * @param deviceId The unique identifier of the device polling for messages.
     * @return A list of pending [SignalingMessage] objects, empty if none.
     */
    @GET("/api/v1/signal/pending/{deviceId}")
    suspend fun getPendingMessages(
        @Path("deviceId") deviceId: String
    ): List<JsonObject>

    /**
     * Polls for pending alert notifications addressed to the parent device.
     *
     * When FCM is unavailable, the parent app polls this endpoint periodically
     * to fetch alerts that were posted by child devices via [sendNotification].
     * Each alert is returned only once and then marked as delivered.
     *
     * @param parentDeviceId The parent device ID to fetch alerts for.
     * @return A list of JSON alert payloads, empty if none pending.
     */
    @GET("/api/v1/notify/pending/{parentDeviceId}")
    suspend fun getPendingAlerts(
        @Path("parentDeviceId") parentDeviceId: String
    ): List<JsonObject>

    /**
     * Sends a guardian notification to the backend for FCM delivery.
     *
     * The backend forwards this metadata-only payload to all registered
     * guardian devices via Firebase Cloud Messaging. No audio, video, or
     * image data is included — only alert metadata.
     *
     * @param childDeviceId The device ID of the child that generated the alert.
     * @param payload The JSON payload containing alert metadata.
     * @return HTTP response indicating success or failure.
     */
    @POST("/api/v1/notify/{childDeviceId}")
    suspend fun sendNotification(
        @Path("childDeviceId") childDeviceId: String,
        @Body payload: JsonObject
    ): Response<Unit>
}
