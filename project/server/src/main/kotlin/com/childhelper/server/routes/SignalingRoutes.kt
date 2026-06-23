package com.childhelper.server.routes

import com.childhelper.core.common.signaling.SdpMessage
import com.childhelper.core.common.signaling.IceMessage
import com.childhelper.core.common.signaling.SignalingMessage
import com.childhelper.server.fcm.FcmDispatcher
import com.childhelper.server.store.MessageStore
import com.childhelper.server.store.PairingStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

private val wsJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

private fun pushToWebSocket(deviceId: String, message: SignalingMessage) {
    try {
        val json = wsJson.encodeToString(SignalingMessage.serializer(), message)
        WebSocketSessions.push(deviceId, json)
    } catch (_: Exception) {}
}

fun Application.signalingRoutes(messageStore: MessageStore, pairingStore: PairingStore, fcmDispatcher: FcmDispatcher) {
    routing {
        route("/api/v1/signal") {
            post("/offer") {
                val offer = call.receive<SignalingMessage>() as? SdpMessage
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid offer"))
                println("[OFFER] from=${offer.fromDeviceId} to=${offer.toDeviceId} session=${offer.sessionId}")
                messageStore.enqueue(offer.toDeviceId, offer)
                pushToWebSocket(offer.toDeviceId, offer)
                fcmDispatcher.sendSignalPoll(offer.toDeviceId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            post("/answer") {
                val answer = call.receive<SignalingMessage>() as? SdpMessage
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid answer"))
                println("[ANSWER] from=${answer.fromDeviceId} to=${answer.toDeviceId} session=${answer.sessionId}")
                messageStore.enqueue(answer.toDeviceId, answer)
                pushToWebSocket(answer.toDeviceId, answer)
                fcmDispatcher.sendSignalPoll(answer.toDeviceId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            post("/ice") {
                val candidate = call.receive<IceMessage>()
                println("[ICE] from=${candidate.fromDeviceId} to=${candidate.toDeviceId} session=${candidate.sessionId}")
                messageStore.enqueue(candidate.toDeviceId, candidate)
                pushToWebSocket(candidate.toDeviceId, candidate)
                fcmDispatcher.sendSignalPoll(candidate.toDeviceId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            get("/pending/{deviceId}") {
                val deviceId = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing deviceId"))
                call.respond(HttpStatusCode.OK, messageStore.dequeueAll(deviceId))
            }
        }
    }
}
