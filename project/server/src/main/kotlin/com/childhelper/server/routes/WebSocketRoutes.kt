package com.childhelper.server.routes

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap

object WebSocketSessions {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    fun register(deviceId: String, session: WebSocketSession) {
        sessions[deviceId] = session
        println("[WS] connected deviceId=${deviceId.take(12)}")
    }

    fun unregister(deviceId: String) {
        sessions.remove(deviceId)
        println("[WS] disconnected deviceId=${deviceId.take(12)}")
    }

    fun push(deviceId: String, messageJson: String) {
        sessions[deviceId]?.outgoing?.trySend(Frame.Text(messageJson))
    }
}

fun Application.webSocketRoutes() {
    install(WebSockets)

    routing {
        webSocket("/api/v1/signal/ws/{deviceId}") {
            val deviceId = call.parameters["deviceId"] ?: return@webSocket close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing deviceId")
            )
            WebSocketSessions.register(deviceId, this)
            try {
                for (frame in incoming) {
                }
            } finally {
                WebSocketSessions.unregister(deviceId)
            }
        }
    }
}
