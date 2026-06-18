package com.childhelper.core.security.di

import android.content.Context
import android.util.Log
import com.childhelper.core.security.EncryptionManager
import com.childhelper.core.security.EncryptionManagerImpl
import com.childhelper.core.security.KeystoreManager
import com.childhelper.core.security.KeystoreManagerImpl
import com.childhelper.core.security.PairingCrypto
import com.childhelper.core.security.PairingCryptoImpl
import com.childhelper.core.security.SecurePreferences
import com.childhelper.core.security.SecurePreferencesImpl
import com.childhelper.core.security.UnpairedSecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module providing all security-layer dependencies.
 *
 * This module is installed in the [SingletonComponent] so that all security
 * objects are application-scoped singletons. This is critical for correctness:
 * - [KeystoreManager] must use the same Keystore instance throughout the app.
 * - [EncryptionManager] is stateless and safe to share.
 * - [SecurePreferences] must use the same DataStore file throughout the app.
 *
 * The module provides two variants of [SecurePreferences]:
 * 1. **Unpaired** — used before device pairing is complete (no encryption).
 * 2. **Paired** — used after pairing (encrypted with shared secret).
 *
 * Apps should inject [SecurePreferences] directly; Hilt will resolve the
 * correct implementation based on available bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
class SecurityModule {

    companion object {

        /**
         * Provides the [KeystoreManager] singleton backed by Android Keystore.
         *
         * All keys are generated and stored in hardware-backed storage when available.
         */
        @Provides
        @Singleton
        fun provideKeystoreManager(): KeystoreManager {
            return KeystoreManagerImpl()
        }

        /**
         * Provides the [EncryptionManager] singleton for AES-256-GCM operations.
         *
         * This is a stateless object safe for concurrent use across all coroutines.
         */
        @Provides
        @Singleton
        fun provideEncryptionManager(): EncryptionManager {
            return EncryptionManagerImpl()
        }

        /**
         * Provides the [PairingCrypto] singleton for pairing code generation
         * and shared secret derivation.
         */
        @Provides
        @Singleton
        fun providePairingCrypto(
            encryptionManager: EncryptionManager
        ): PairingCrypto {
            return PairingCryptoImpl(encryptionManager)
        }

        /**
         * Provides the pre-pairing [SecurePreferences] implementation.
         *
         * This stores data **without encryption** and is intended only for
         * device ID and pairing state before a shared secret exists.
         *
         * After pairing completes, apps should migrate to an encrypted
         * [SecurePreferences] using the derived shared secret.
         */
        @Provides
        @Singleton
        @UnpairedSecurePrefs
        fun provideUnpairedSecurePreferences(
            @ApplicationContext context: Context
        ): SecurePreferences {
            return UnpairedSecurePreferences(context)
        }

        /**
         * Provides the post-pairing [SecurePreferencesImpl] instance.
         *
         * This encrypts all values using AES-256-GCM with a device-specific key
         * derived from the Android Keystore. Before pairing completes, a placeholder
         * secret is derived from the device's Keystore-backed public key. After
         * pairing, the real shared secret from ECDH key agreement replaces it.
         *
         * If the Keystore is unavailable, falls back to [UnpairedSecurePreferences]
         * to ensure the app remains functional.
         */
        @Provides
        @Singleton
        fun provideSecurePreferencesImpl(
            @ApplicationContext context: Context,
            encryptionManager: EncryptionManager,
            keystoreManager: KeystoreManager
        ): SecurePreferences {
            return try {
                // Generate or retrieve a device-specific key pair from Keystore.
                // Derive a 32-byte placeholder secret from the public key bytes
                // using SHA-256. This serves as the shared secret until real
                // pairing occurs and the ECDH-derived secret is available.
                val keyPair = keystoreManager.generateKeyPair("child_device_prefs_key")
                val publicKeyBytes = keyPair.public.encoded
                    ?: return UnpairedSecurePreferences(context)

                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val sharedSecret = digest.digest(publicKeyBytes)

                SecurePreferencesImpl(
                    context = context,
                    encryptionManager = encryptionManager,
                    sharedSecret = sharedSecret
                )
            } catch (e: Exception) {
                // Fall back to unpaired preferences if Keystore is unavailable.
                // This ensures the app remains functional on devices where
                // Keystore access may be restricted.
                Log.w("SecurityModule", "Keystore key generation failed, falling back to unpaired preferences", e)
                UnpairedSecurePreferences(context)
            }
        }
    }
}

/**
 * Qualifier annotation for distinguishing between paired and unpaired
 * [SecurePreferences] implementations when both are available.
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PairedSecurePrefs

/**
 * Qualifier annotation for the unpaired (pre-pairing) [SecurePreferences]
 * implementation.
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnpairedSecurePrefs
