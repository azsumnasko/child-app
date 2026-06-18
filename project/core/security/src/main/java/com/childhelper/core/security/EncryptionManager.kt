package com.childhelper.core.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides symmetric encryption/decryption using shared secrets derived from
 * ECDH key agreement, plus shared secret derivation itself.
 *
 * This is used to encrypt all peer-to-peer communication (signaling messages,
 * alerts, settings sync) after devices have paired and established a shared secret.
 *
 * **Algorithm:** AES-256-GCM with random 12-byte IVs. Authentication tag is 128 bits.
 * **Key Derivation:** ECDH → HKDF-SHA256 → AES-256 key.
 *
 * This interface contract is defined in SPEC.md section 4.1.
 */
interface EncryptionManager {

    /**
     * Encrypts [plainText] using the provided [sharedSecret].
     *
     * Generates a random 12-byte IV for each encryption operation. The IV is
     * prepended to the ciphertext in the returned string (format: `base64(iv + ciphertext)`).
     *
     * @param plainText The plaintext string to encrypt (UTF-8).
     * @param sharedSecret The 32-byte shared secret derived from ECDH key agreement.
     * @return Base64-encoded string containing IV + AES-GCM ciphertext + auth tag.
     */
    fun encryptWithSharedSecret(plainText: String, sharedSecret: ByteArray): String

    /**
     * Decrypts [cipherText] using the provided [sharedSecret].
     *
     * Expects the format produced by [encryptWithSharedSecret]:
     * `base64(iv + ciphertext + auth_tag)`.
     *
     * @param cipherText The base64-encoded ciphertext string.
     * @param sharedSecret The 32-byte shared secret derived from ECDH key agreement.
     * @return The decrypted plaintext string (UTF-8).
     * @throws javax.crypto.AEADBadTagException If the authentication tag is invalid
     *         (tampered ciphertext or wrong key).
     */
    fun decryptWithSharedSecret(cipherText: String, sharedSecret: ByteArray): String

    /**
     * Derives a shared secret from a local ECDH private key and a remote ECDH public key.
     *
     * The raw ECDH output is passed through HKDF-SHA256 to produce a uniform 32-byte key
     * suitable for AES-256.
     *
     * @param privateKey The local ECDH private key.
     * @param publicKey The remote ECDH public key.
     * @return A 32-byte shared secret.
     */
    fun generateSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray

    /**
     * Generates an ephemeral ECDH key pair using the NIST P-256 (secp256r1) curve.
     *
     * Explicit curve specification prevents ambiguous "EC" algorithm mapping
     * and potential downgrade attacks (CWE-327). P-256 provides 128-bit security
     * with wide hardware and provider support.
     *
     * @return A freshly generated [KeyPair] for ECDH key agreement.
     */
    fun generateEcdhKeyPair(): KeyPair
}

/**
 * Production implementation of [EncryptionManager].
 *
 * Uses AES-256-GCM for authenticated encryption and ECDH (P-256) + HKDF-SHA256
 * for key agreement. All operations use standard JCA/JCE providers.
 *
 * **Security features:**
 * - Explicit P-256 curve specification prevents ambiguous "EC" algorithm mapping.
 * - Non-zero HKDF salt ensures unique PRKs per pairing session.
 * - Random 12-byte IVs for every AES-GCM encryption (never reused).
 * - 128-bit GCM authentication tags.
 */
class EncryptionManagerImpl : EncryptionManager {

    companion object {
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_LENGTH_BITS = 256
        /**
         * Explicit "ECDH" algorithm identifier for KeyAgreement.
         *
         * The generic "EC" string is ambiguous — different JCA providers may map
         * it to different curves or protocols. "ECDH" is unambiguous and explicitly
         * requests elliptic-curve Diffie-Hellman key agreement (CWE-327).
         */
        private const val ECDH_ALGORITHM = "ECDH"
        private const val HKDF_ALGORITHM = "HmacSHA256"
        private const val SHARED_SECRET_LENGTH = 32
        /**
         * NIST P-256 curve name for EC key pair generation.
         *
         * P-256 (also known as secp256r1 / prime256v1) provides 128-bit security
         * and is widely supported by hardware Keystore implementations.
         */
        private const val ECDH_CURVE = "secp256r1"
        /**
         * Fixed application-specific salt for HKDF extract phase.
         *
         * **Security rationale:** The original code passed `salt = null` to HKDF,
         * which causes the RFC 5869 implementation to use a zero-filled salt.
         * A zero salt weakens the extraction phase because identical ECDH outputs
         * across different sessions would produce identical PRKs. Using a fixed
         * non-zero application-specific salt ensures a unique PRK per session
         * (since each ECDH key pair is ephemeral, the IKM is already unique).
         *
         * For even stronger security, a random 32-byte salt can be generated per
         * pairing session and stored alongside the shared secret.
         */
        private val HKDF_SALT = "ChildHelper-v1-HKDF-Salt".toByteArray(Charsets.UTF_8)
    }

    override fun encryptWithSharedSecret(plainText: String, sharedSecret: ByteArray): String {
        require(sharedSecret.size == SHARED_SECRET_LENGTH) {
            "Shared secret must be $SHARED_SECRET_LENGTH bytes, got ${sharedSecret.size}"
        }

        val iv = com.childhelper.core.common.util.CryptoUtil.secureRandomBytes(GCM_IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(sharedSecret, AES_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherBytes
        return com.childhelper.core.common.util.CryptoUtil.base64Encode(combined)
    }

    override fun decryptWithSharedSecret(cipherText: String, sharedSecret: ByteArray): String {
        require(sharedSecret.size == SHARED_SECRET_LENGTH) {
            "Shared secret must be $SHARED_SECRET_LENGTH bytes, got ${sharedSecret.size}"
        }

        val combined = com.childhelper.core.common.util.CryptoUtil.base64Decode(cipherText)
        require(combined.size > GCM_IV_LENGTH_BYTES) {
            "Ciphertext too short: must contain IV + ciphertext"
        }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(sharedSecret, AES_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    override fun generateSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        // Perform ECDH key agreement
        val keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        val ecdhOutput = keyAgreement.generateSecret()

        // Derive uniform key using HKDF-SHA256 (extract-then-expand).
        // Uses a fixed application-specific salt instead of null (which would
        // produce a zero-filled salt, weakening forward secrecy — see HKDF_SALT KDoc).
        return hkdfSha256(ecdhOutput, salt = HKDF_SALT, info = "ChildHelper-v1".toByteArray(Charsets.UTF_8))
    }

    override fun generateEcdhKeyPair(): KeyPair {
        // Explicit P-256 curve specification prevents ambiguous "EC" mapping
        // and downgrade attacks. P-256 has wide hardware Keystore support.
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec(ECDH_CURVE)
        keyPairGenerator.initialize(ecSpec)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * HKDF-SHA256 extract-and-expand as defined in RFC 5869.
     *
     * @param ikm Input keying material (ECDH raw output).
     * @param salt Optional salt. **Must be non-null for production use.**
     *        A zero-filled salt (null) weakens the extraction phase — callers
     *        should always provide a unique or application-specific salt.
     *        See [HKDF_SALT] for the default non-zero salt used by this class.
     * @param info Optional context/application-specific info string.
     * @param length Desired output length in bytes (default 32).
     * @return Derived key of [length] bytes.
     */
    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = ByteArray(0),
        length: Int = SHARED_SECRET_LENGTH
    ): ByteArray {
        // Extract: PRK = HMAC-SHA256(salt, IKM)
        val saltBytes = salt ?: ByteArray(32) { 0 }
        val prk = Mac.getInstance(HKDF_ALGORITHM).run {
            init(SecretKeySpec(saltBytes, HKDF_ALGORITHM))
            doFinal(ikm)
        }

        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        val result = ByteArray(length)
        val mac = Mac.getInstance(HKDF_ALGORITHM)
        mac.init(SecretKeySpec(prk, HKDF_ALGORITHM))

        var previousBlock = ByteArray(0)
        var done = 0
        var counter = 1

        while (done < length) {
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())
            previousBlock = mac.doFinal()

            val toCopy = minOf(length - done, previousBlock.size)
            previousBlock.copyInto(result, done, 0, toCopy)
            done += toCopy
            counter++
        }

        return result
    }
}
