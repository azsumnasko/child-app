package com.childhelper.server.routes

import com.childhelper.core.common.signaling.SdpMessage
import com.childhelper.core.common.signaling.IceMessage
import com.childhelper.server.store.MessageStore
import com.childhelper.server.store.PairingStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.signalingRoutes(messageStore: MessageStore, pairingStore: PairingStore) {
    routing {
        route("/api/v1/signal") {
            post("/offer") {
                val offer = call.receive<SdpMessage>()
                if (!pairingStore.arePaired(offer.fromDeviceId, offer.toDeviceId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Devices are not paired"))
                }
                messageStore.enqueue(offer.toDeviceId, offer)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            post("/answer") {
                val answer = call.receive<SdpMessage>()
                if (!pairingStore.arePaired(answer.fromDeviceId, answer.toDeviceId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Devices are not paired"))
                }
                messageStore.enqueue(answer.toDeviceId, answer)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            post("/ice") {
                val candidate = call.receive<IceMessage>()
                if (!pairingStore.arePaired(candidate.fromDeviceId, candidate.toDeviceId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Devices are not paired"))
                }
                messageStore.enqueue(candidate.toDeviceId, candidate)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
            get("/pending/{deviceId}") {
                val deviceId = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing deviceId"))
                call.respond(HttpStatusCode.OK, messageStore.dequeueAll(deviceId))
            }
        }
    }
}
