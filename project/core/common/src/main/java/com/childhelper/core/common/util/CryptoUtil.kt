package com.childhelper.core.common.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Low-level cryptographic utilities used across the security module.
 *
 * These are pure functions with no side effects — they perform computations
 * only and do **not** access the Android Keystore or store any secrets.
 *
 * All functions are thread-safe and suitable for use from any coroutine dispatcher.
 */
object CryptoUtil {

    private val secureRandom = java.security.SecureRandom()

    /**
     * Generates cryptographically secure random bytes.
     *
     * Uses [SecureRandom] which delegates to the platform's strongest
     * entropy source (e.g., /dev/urandom on Linux/Android).
     *
     * @param length Number of random bytes to generate.
     * @return A byte array containing [length] random bytes.
     */
    fun secureRandomBytes(length: Int = 32): ByteArray {
        require(length > 0) { "Length must be positive, got: $length" }
        return ByteArray(length).also { secureRandom.nextBytes(it) }
    }

    /**
     * Generates a random 6-character alphanumeric pairing code.
     *
     * The code uses uppercase letters (excluding I, O to avoid confusion)
     * and digits (excluding 0, 1 to avoid confusion), giving a pool of
     * 32 characters. This yields ~1 billion possible combinations,
     * sufficient for short-lived 5-minute pairing windows.
     *
     * Characters excluded: I, O, 0, 1
     *
     * @return A 6-character alphanumeric string safe for visual display.
     */
    fun generatePairingCode(): String {
        val charset = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return CharArray(6) { charset[random.nextInt(charset.length)] }.concatToString()
    }

    /**
     * Computes the SHA-256 digest of the given data.
     *
     * @param data Input bytes to hash.
     * @return 32-byte SHA-256 digest.
     */
    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").run {
            update(data)
            digest()
        }
    }

    /**
     * Encodes a byte array to a Base64 string (URL-safe, no padding).
     *
     * URL-safe encoding replaces `+` → `-` and `/` → `_`, and omits `=` padding.
     * This is suitable for transmitting binary data in JSON and URLs.
     *
     * @param bytes The bytes to encode.
     * @return Base64-url-encoded string without padding.
     */
    fun base64Encode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Decodes a Base64 string (URL-safe, with or without padding) to a byte array.
     *
     * @param encoded The Base64-encoded string.
     * @return The decoded bytes.
     * @throws IllegalArgumentException if the string is not valid Base64.
     */
    fun base64Decode(encoded: String): ByteArray {
        // Add padding if needed for URL-safe Base64
        val padded = when (encoded.length % 4) {
            2 -> "$encoded=="
            3 -> "$encoded="
            else -> encoded
        }
        return Base64.getUrlDecoder().decode(padded)
    }

    /**
     * Derives a display fingerprint from a public key for manual verification.
     *
     * Computes SHA-256 of the key and returns the first 8 characters of the
     * hex-encoded digest. This is shown to users during pairing to detect
     * man-in-the-middle attacks.
     *
     * @param publicKeyBytes The raw public key bytes.
     * @return An 8-character hex string fingerprint.
     */
    fun fingerprintPublicKey(publicKeyBytes: ByteArray): String {
        val digest = sha256(publicKeyBytes)
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time comparison of two byte arrays to prevent timing attacks.
     *
     * Unlike [ByteArray.contentEquals], this function always takes the same
     * amount of time regardless of where the arrays differ.
     *
     * @param a First byte array.
     * @param b Second byte array.
     * @return `true` if the arrays are identical in content.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        var result = a.size xor b.size
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
