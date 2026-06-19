package com.childhelper.server.turn

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Generates time-limited TURN credentials for coturn's `--use-auth-secret` mode.
 *
 * Username format: `<expiry_unix_timestamp>:<user>`
 * Password: base64(HMAC-SHA1(secret, username))
 */
object TurnCredentialGenerator {
    fun generate(
        secret: String,
        user: String,
        ttlSeconds: Long,
    ): TurnCredentials {
        require(secret.isNotBlank()) { "TURN secret must not be blank" }
        require(user.isNotBlank()) { "TURN user must not be blank" }
        require(ttlSeconds > 0) { "TURN credential TTL must be positive" }

        val expiry = System.currentTimeMillis() / 1000 + ttlSeconds
        val username = "$expiry:$user"
        val password = hmacSha1Base64(secret, username)
        return TurnCredentials(username = username, password = password)
    }

    private fun hmacSha1Base64(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }
}

data class TurnCredentials(val username: String, val password: String)
