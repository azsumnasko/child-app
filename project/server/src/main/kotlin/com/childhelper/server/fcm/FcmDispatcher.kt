package com.childhelper.server.fcm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FcmDispatcher {
    private val log = LoggerFactory.getLogger(FcmDispatcher::class.java)
    private val tokens = ConcurrentHashMap<String, String>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun registerToken(deviceId: String, token: String) {
        tokens[deviceId] = token
        log.info("FCM token registered for device $deviceId")
    }

    fun unregisterToken(deviceId: String) { tokens.remove(deviceId) }

    fun sendAlert(
        targetDeviceId: String, alertId: String, eventType: String, timestamp: Long,
        childDeviceId: String, priority: String, confidence: Float?, batteryPercent: Int,
        isCharging: Boolean, networkType: String, monitorMode: String
    ): Boolean {
        val token = tokens[targetDeviceId]
        if (token == null) { log.warn("No FCM token for device $targetDeviceId"); return false }

        val accessToken = System.getenv("FCM_ACCESS_TOKEN")
        val projectId = System.getenv("FIREBASE_PROJECT_ID")
        if (accessToken == null || projectId == null) {
            log.info("FCM not configured — would send alert $alertId")
            return false
        }

        return try {
            val dataPayload = buildJsonObject {
                put("type", "alert"); put("alertId", alertId); put("eventType", eventType)
                put("timestamp", timestamp.toString()); put("childDeviceId", childDeviceId)
                put("priority", priority)
                confidence?.let { put("confidence", it.toString()) }
                put("batteryPercent", batteryPercent.toString())
                put("isCharging", isCharging.toString())
                put("networkType", networkType); put("monitorMode", monitorMode)
            }

            val fcmPayload = buildJsonObject {
                put("message", buildJsonObject {
                    put("token", token)
                    put("data", dataPayload)
                    put("android", buildJsonObject { put("priority", "high") })
                })
            }

            val url = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
            val body = Json.encodeToString(JsonObject.serializer(), fcmPayload)
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json").build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) { log.debug("FCM alert sent: $alertId"); true }
                else { log.warn("FCM send failed: ${response.code}"); false }
            }
        } catch (e: Exception) { log.error("FCM error for $alertId", e); false }
    }

    fun sendSignalPoll(deviceId: String): Boolean {
        val token = tokens[deviceId] ?: return false
        val accessToken = System.getenv("FCM_ACCESS_TOKEN") ?: return false
        val projectId = System.getenv("FIREBASE_PROJECT_ID") ?: return false

        return try {
            val fcmPayload = buildJsonObject {
                put("message", buildJsonObject {
                    put("token", token)
                    put("data", buildJsonObject { put("type", "signal_poll") })
                })
            }
            val url = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
            val body = Json.encodeToString(JsonObject.serializer(), fcmPayload)
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json").build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }
}
