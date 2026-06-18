package com.childhelper.server.routes

import com.childhelper.server.store.PairingStore
import com.childhelper.server.fcm.FcmDispatcher
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Application.notificationRoutes(fcm: FcmDispatcher, pairingStore: PairingStore) {
    routing {
        post("/api/v1/notify/{childDeviceId}") {
            val childDeviceId = call.parameters["childDeviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing childDeviceId"))
            val parentDeviceId = pairingStore.getParentDeviceId(childDeviceId)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No parent paired with this child device"))

            val payload = call.receive<JsonObject>()
            val alertId = payload["alertId"]?.jsonPrimitive?.content ?: "unknown"
            val eventType = payload["eventType"]?.jsonPrimitive?.content ?: "UNKNOWN"
            val timestamp = payload["timestamp"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
            val priority = payload["priority"]?.jsonPrimitive?.content ?: "normal"
            val confidence = payload["confidence"]?.jsonPrimitive?.float
            val batteryPercent = payload["batteryPercent"]?.jsonPrimitive?.int ?: -1
            val isCharging = payload["isCharging"]?.jsonPrimitive?.boolean ?: false
            val networkType = payload["networkType"]?.jsonPrimitive?.content ?: "unknown"
            val monitorMode = payload["monitorMode"]?.jsonPrimitive?.content ?: "IDLE"

            val success = fcm.sendAlert(
                targetDeviceId = parentDeviceId,
                alertId = alertId, eventType = eventType, timestamp = timestamp,
                childDeviceId = childDeviceId, priority = priority, confidence = confidence,
                batteryPercent = batteryPercent, isCharging = isCharging,
                networkType = networkType, monitorMode = monitorMode
            )
            if (success) call.respond(HttpStatusCode.OK, mapOf("status" to "delivered"))
            else call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted", "fcm" to "not_configured"))
        }

        post("/api/v1/register-token") {
            val body = call.receive<JsonObject>()
            val deviceId = body["deviceId"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing deviceId"))
            val token = body["fcmToken"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fcmToken"))
            fcm.registerToken(deviceId, token)
            call.respond(HttpStatusCode.OK, mapOf("status" to "registered"))
        }
    }
}
