package com.childhelper.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Manages asymmetric cryptographic keys inside the Android Keystore.
 *
 * Keys are generated with hardware-backed protection when available (TEE or StrongBox).
 * Private keys never leave the Keystore boundary and cannot be extracted by the app
 * or any other process.
 *
 * **Privacy:** All keys are stored in Android Keystore. No key material exists in
 * application code, shared preferences, or files.
 *
 * This interface contract is defined in SPEC.md section 4.1.
 */
interface KeystoreManager {

    /**
     * Generates a new RSA-2048 key pair inside the Android Keystore under [alias].
     *
     * If a key pair already exists for [alias], it is **not** replaced — the
     * existing key pair is returned.
     *
     * Keys are configured for:
     * - RSA-OAEP padding with SHA-256 (RSA/ECB/OAEPWithSHA-256AndMGF1Padding)
     *   This mitigates Bleichenbacher padding-oracle attacks that affect PKCS#1 v1.5.
     * - Hardware-backed storage (StrongBox preferred, TEE fallback)
     * - No user authentication required
     *
     * @param alias The logical name for the key pair (e.g., `"child_device_key"`).
     * @return The generated or existing [KeyPair] (public key extractable, private key not).
     */
    fun generateKeyPair(alias: String): KeyPair

    /**
     * Retrieves the public key associated with [alias] from the Keystore.
     *
     * @param alias The key alias to look up.
     * @return The [PublicKey] if it exists, or `null` if no key is found.
     */
    fun getPublicKey(alias: String): PublicKey?

    /**
     * Decrypts data that was previously encrypted with the public key of [alias].
     *
     * Decryption is performed inside the Keystore boundary using the private key.
     *
     * @param alias The key alias whose private key should perform decryption.
     * @param encryptedData The ciphertext bytes to decrypt.
     * @return The decrypted plaintext bytes.
     * @throws javax.crypto.BadPaddingException If the ciphertext is malformed.
     * @throws java.security.InvalidKeyException If no private key exists for [alias].
     */
    fun decrypt(alias: String, encryptedData: ByteArray): ByteArray

    /**
     * Encrypts plaintext using the public key of [alias].
     *
     * Encryption is performed with RSA-OAEP (SHA-256 with MGF1-SHA-256).
     * OAEP provides semantic security and mitigates Bleichenbacher attacks
     * that affect the older PKCS#1 v1.5 padding. The resulting ciphertext
     * can only be decrypted by the corresponding private key held in the Keystore.
     *
     * @param alias The key alias whose public key should perform encryption.
     * @param plainData The plaintext bytes to encrypt.
     * @return The encrypted ciphertext bytes.
     * @throws java.security.InvalidKeyException If no public key exists for [alias].
     */
    fun encrypt(alias: String, plainData: ByteArray): ByteArray

    /**
     * Permanently deletes the key pair associated with [alias] from the Keystore.
     *
     * This operation is irreversible. Any data encrypted with the public key
     * will become permanently undecryptable.
     *
     * @param alias The key alias to delete.
     */
    fun removeKey(alias: String)
}

/**
 * Production implementation of [KeystoreManager] backed by Android Keystore.
 *
 * Uses **RSA-OAEP with SHA-256** for all asymmetric encryption/decryption,
 * which provides strong protection against Bleichenbacher padding-oracle
 * attacks that affect the legacy PKCS#1 v1.5 padding scheme.
 *
 * @property provider The JCA provider name (default: `"AndroidKeyStore"`).
 */
class KeystoreManagerImpl(
    private val provider: String = "AndroidKeyStore"
) : KeystoreManager {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(provider).apply { load(null) }
    }

    companion object {
        private const val TAG = "KeystoreManager"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_RSA
        private const val KEY_SIZE = 2048
        /**
         * RSA-OAEP with SHA-256 and MGF1-SHA-256.
         *
         * OAEP (Optimal Asymmetric Encryption Padding) is used instead of PKCS#1 v1.5
         * to mitigate Bleichenbacher padding-oracle attacks (CWE-327).
         * The digest is SHA-256 and the mask generation function (MGF1) also uses SHA-256.
         */
        private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /**
         * OAEP parameters used for both encryption and decryption.
         * SHA-256 for the main digest, MGF1 with SHA-256, and default (empty) label.
         */
        private val OAEP_PARAMS = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
    }

    override fun generateKeyPair(alias: String): KeyPair {
        // Return existing key if present
        getExistingKeyPair(alias)?.let { return it }

        try {
            return generateKeyPairInternal(alias, tryStrongBox = true)
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox key generation failed, falling back to software-backed keys", e)
            try {
                return generateKeyPairInternal(alias, tryStrongBox = false)
            } catch (e2: Exception) {
                throw KeystoreException("Failed to generate key pair for alias: $alias", e2)
            }
        }
    }

    private fun generateKeyPairInternal(alias: String, tryStrongBox: Boolean): KeyPair {
        val specBuilder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
        ).apply {
            setKeySize(KEY_SIZE)
            setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)

            if (tryStrongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                runCatching { setIsStrongBoxBacked(true) }
            }
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, provider)
        keyPairGenerator.initialize(specBuilder.build())
        return keyPairGenerator.generateKeyPair()
    }

    class KeystoreException(message: String, cause: Throwable) : Exception(message, cause)

    override fun getPublicKey(alias: String): PublicKey? {
        return if (keyStore.containsAlias(alias)) {
            keyStore.getCertificate(alias)?.publicKey
        } else {
            null
        }
    }

    override fun decrypt(alias: String, encryptedData: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: throw InvalidKeyException("No private key found for alias: $alias")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_PARAMS)
        return cipher.doFinal(encryptedData)
    }

    override fun encrypt(alias: String, plainData: ByteArray): ByteArray {
        val publicKey = getPublicKey(alias)
            ?: throw InvalidKeyException("No public key found for alias: $alias")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_PARAMS)
        return cipher.doFinal(plainData)
    }

    override fun removeKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    /**
     * Returns the existing key pair for [alias] if both private and public keys exist.
     */
    private fun getExistingKeyPair(alias: String): KeyPair? {
        if (!keyStore.containsAlias(alias)) return null
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return null
        val publicKey = keyStore.getCertificate(alias)?.publicKey ?: return null
        return KeyPair(publicKey, privateKey)
    }

    /**
     * Exception thrown when a Keystore key operation fails due to the key
     * not being found or being invalid.
     */
    class InvalidKeyException(message: String) : Exception(message)
}
