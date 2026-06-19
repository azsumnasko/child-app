package com.childhelper.server.routes

import com.childhelper.server.store.PairingStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class InitiatePairingRequest(val childDeviceId: String, val childPublicKey: String)
@Serializable data class CompletePairingRequest(val sessionId: String, val parentDeviceId: String, val parentPublicKey: String, val pairingCode: String)
@Serializable data class RevokePairingRequest(val sessionId: String, val deviceId: String)

fun Application.pairingRoutes(store: PairingStore) {
    routing {
        route("/api/v1/pairing") {
            post("/initiate") {
                val req = call.receive<InitiatePairingRequest>()
                val session = store.createSession(req.childDeviceId, req.childPublicKey)
                call.respond(HttpStatusCode.Created, session)
            }
            post("/complete") {
                val req = call.receive<CompletePairingRequest>()
                val session = store.completePairing(req.sessionId, req.parentDeviceId, req.parentPublicKey, req.pairingCode)
                if (session != null) call.respond(HttpStatusCode.OK, session)
                else call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired pairing session"))
            }
            post("/revoke") {
                val req = call.receive<RevokePairingRequest>()
                if (store.revokeSession(req.sessionId)) call.respond(HttpStatusCode.OK, mapOf("status" to "revoked"))
                else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            }
            get("/status/{sessionId}") {
                val sessionId = call.parameters["sessionId"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
                val session = store.getSession(sessionId)
                if (session != null) call.respond(HttpStatusCode.OK, session)
                else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            }
        }
    }
}
