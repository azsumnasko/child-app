package com.childhelper.server

import com.childhelper.server.routes.notificationRoutes
import com.childhelper.server.routes.pairingRoutes
import com.childhelper.server.routes.signalingRoutes
import com.childhelper.server.routes.turnRoutes
import com.childhelper.server.routes.webSocketRoutes
import com.childhelper.server.store.AlertStore
import com.childhelper.server.store.MessageStore
import com.childhelper.server.store.PairingStore
import com.childhelper.server.fcm.FcmDispatcher
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

fun main() {
    val pairingStore = PairingStore()
    val messageStore = MessageStore()
    val alertStore = AlertStore()
    val fcmDispatcher = FcmDispatcher()

    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
                explicitNulls = false
            })
        }

        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: "Internal server error"))
                )
            }
        }

        pairingRoutes(pairingStore)
        signalingRoutes(messageStore, pairingStore, fcmDispatcher)
        webSocketRoutes()
        notificationRoutes(fcmDispatcher, pairingStore, alertStore)
        turnRoutes()
    }.start(wait = true)
}
