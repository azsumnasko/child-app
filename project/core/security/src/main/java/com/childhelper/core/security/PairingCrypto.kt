package com.childhelper.core.security

import com.childhelper.core.common.model.PairingSession
import com.childhelper.core.common.model.PairingStatus
import com.childhelper.core.common.util.CryptoUtil
import java.security.KeyPair
import java.security.PublicKey

/**
 * Cryptographic operations related to the device pairing flow.
 *
 * Pairing establishes a trust relationship between a child device and a parent
 * device through a short-lived verification code + ECDH key exchange. Once
 * completed, both devices share a symmetric secret used for all future
 * end-to-end encrypted communication.
 *
 * This interface contract is defined in SPEC.md section 4.1.
 */
interface PairingCrypto {

    /**
     * Generates a new pairing code for display on the child device.
     *
     * The code is 6 characters long, using a reduced character set that
     * excludes visually ambiguous characters (I, O, 0, 1).
     *
     * @return A 6-character uppercase alphanumeric pairing code.
     */
    fun generatePairingCode(): String

    /**
     * Derives a shared secret from the child's ECDH key pair and the parent's
     * ECDH public key received during pairing completion.
     *
     * Both devices derive the **same** shared secret using ECDH key agreement
     * followed by HKDF-SHA256 key derivation.
     *
     * @param childKeyPair The child's ECDH key pair (generated during pairing initiation).
     * @param parentPublicKey The parent's ECDH public key (received from pairing server).
     * @return A 32-byte shared secret for AES-256-GCM encryption.
     */
    fun deriveSharedSecret(childKeyPair: KeyPair, parentPublicKey: PublicKey): ByteArray

    /**
     * Verifies that a pairing code entered by the parent matches the session
     * and has not expired.
     *
     * Performs constant-time comparison to prevent timing attacks on the code.
     *
     * @param code The 6-character code entered by the parent.
     * @param session The pairing session containing the expected code.
     * @return `true` if the code matches and the session is still pending and not expired.
     */
    fun verifyPairingCode(code: String, session: PairingSession): Boolean

    /**
     * Generates an ephemeral ECDH key pair using the NIST P-256 (secp256r1) curve.
     *
     * Explicit curve specification prevents ambiguous "EC" algorithm mapping
     * and downgrade attacks. The generated key pair is ephemeral — it should be
     * used for a single pairing session and then discarded.
     *
     * @return A freshly generated [KeyPair] for ECDH key agreement.
     */
    fun generateEcdhKeyPair(): KeyPair
}

/**
 * Production implementation of [PairingCrypto].
 *
 * @property encryptionManager Used to derive the shared secret via ECDH + HKDF.
 */
class PairingCryptoImpl(
    private val encryptionManager: EncryptionManager
) : PairingCrypto {

    override fun generatePairingCode(): String {
        return CryptoUtil.generatePairingCode()
    }

    override fun deriveSharedSecret(childKeyPair: KeyPair, parentPublicKey: PublicKey): ByteArray {
        return encryptionManager.generateSharedSecret(
            privateKey = childKeyPair.private,
            publicKey = parentPublicKey
        )
    }

    override fun verifyPairingCode(code: String, session: PairingSession): Boolean {
        // Check session is still pending
        if (session.status != PairingStatus.PENDING) {
            return false
        }

        // Check code has not expired
        val now = System.currentTimeMillis()
        if (now > session.expiresAt) {
            return false
        }

        // Validate code format (6 uppercase alphanumeric characters)
        if (!code.matches(Regex("^[A-HJ-NP-Z2-9]{6}$"))) {
            return false
        }

        // Constant-time comparison to prevent timing attacks
        return CryptoUtil.constantTimeEquals(
            code.toByteArray(Charsets.UTF_8),
            session.pairingCode.toByteArray(Charsets.UTF_8)
        )
    }

    override fun generateEcdhKeyPair(): KeyPair {
        return encryptionManager.generateEcdhKeyPair()
    }
}
