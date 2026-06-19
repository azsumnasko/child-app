package com.childhelper.server.routes

import com.childhelper.server.turn.TurnCredentialGenerator
import com.childhelper.server.turn.TurnCredentials
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable data class TurnCredentialsResponse(val username: String, val password: String, val urls: List<String>, val stunUrls: List<String>)

private val turnLog = LoggerFactory.getLogger("TurnRoutes")

fun Application.turnRoutes() {
    routing {
        post("/api/v1/turn/credentials") {
            val turnUrl = System.getenv("TURN_SERVER_URL") ?: "turn:turn.childhelper.com:3478"
            val stunUrl = System.getenv("STUN_SERVER_URL") ?: "stun:stun.l.google.com:19302"
            val turnUser = System.getenv("TURN_USERNAME") ?: "childhelper"
            val turnSecret = System.getenv("TURN_SECRET")

            val credentials = if (!turnSecret.isNullOrBlank()) {
                val ttlSeconds = System.getenv("TURN_CREDENTIAL_TTL_SECONDS")?.toLongOrNull() ?: 86_400L
                TurnCredentialGenerator.generate(
                    secret = turnSecret,
                    user = turnUser,
                    ttlSeconds = ttlSeconds,
                )
            } else {
                turnLog.warn(
                    "TURN_SECRET is not set; returning static TURN_USERNAME/TURN_PASSWORD. " +
                        "Configure TURN_SECRET to match coturn --static-auth-secret."
                )
                val turnPassword = System.getenv("TURN_PASSWORD")
                    ?: (1..32).map { ('A'..'Z') + ('a'..'z') + ('0'..'9') }.joinToString("")
                TurnCredentials(username = turnUser, password = turnPassword)
            }

            call.respond(
                HttpStatusCode.OK,
                TurnCredentialsResponse(
                    username = credentials.username,
                    password = credentials.password,
                    urls = listOf(turnUrl, "$turnUrl?transport=tcp"),
                    stunUrls = listOf(stunUrl),
                ),
            )
        }
    }
}
