package com.childhelper.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class TurnCredentialsResponse(val username: String, val password: String, val urls: List<String>, val stunUrls: List<String>)

fun Application.turnRoutes() {
    routing {
        post("/api/v1/turn/credentials") {
            val turnUsername = System.getenv("TURN_USERNAME") ?: "childhelper_turn_user"
            val turnPassword = System.getenv("TURN_PASSWORD") ?: (1..32).map { ('A'..'Z') + ('a'..'z') + ('0'..'9') }.joinToString("")
            val turnUrl = System.getenv("TURN_SERVER_URL") ?: "turn:turn.childhelper.com:3478"
            val stunUrl = System.getenv("STUN_SERVER_URL") ?: "stun:stun.l.google.com:19302"

            call.respond(HttpStatusCode.OK, TurnCredentialsResponse(
                username = turnUsername, password = turnPassword,
                urls = listOf(turnUrl, "$turnUrl?transport=tcp"),
                stunUrls = listOf(stunUrl)
            ))
        }
    }
}
