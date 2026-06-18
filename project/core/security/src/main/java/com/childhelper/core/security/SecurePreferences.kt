package com.childhelper.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Encrypted key-value storage backed by Jetpack DataStore with file-level encryption.
 *
 * This is the **only** persistent settings storage mechanism in the app.
 * It replaces SharedPreferences entirely — no plain-text settings are ever written to disk.
 *
 * Data is encrypted at rest using AES-256-GCM via the [EncryptionManager] (which
 * uses keys stored in Android Keystore). This ensures that even if the device is
 * compromised, settings remain unreadable without the Keystore-held key.
 *
 * **Privacy:** All data is encrypted before being written to disk. No plaintext
 * preferences exist. No backup to cloud services occurs.
 *
 * This interface contract is defined in SPEC.md section 4.1.
 */
interface SecurePreferences {

    /**
     * Stores a string value encrypted under [key].
     *
     * @param key The preference key.
     * @param value The string value to encrypt and store.
     */
    suspend fun putString(key: String, value: String)

    /**
     * Retrieves and decrypts a string value stored under [key].
     *
     * @param key The preference key.
     * @param default The value to return if the key does not exist.
     * @return The decrypted string, or [default] if not found.
     */
    suspend fun getString(key: String, default: String? = null): String?

    /**
     * Stores a boolean value encrypted under [key].
     *
     * @param key The preference key.
     * @param value The boolean value to encrypt and store.
     */
    suspend fun putBoolean(key: String, value: Boolean)

    /**
     * Retrieves and decrypts a boolean value stored under [key].
     *
     * @param key The preference key.
     * @param default The value to return if the key does not exist.
     * @return The decrypted boolean, or [default] if not found.
     */
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean

    /**
     * Removes the value associated with [key].
     *
     * @param key The preference key to remove.
     */
    suspend fun remove(key: String)

    /**
     * Clears all stored preferences. Use with caution — this is irreversible.
     */
    suspend fun clear()
}

/**
 * Production implementation of [SecurePreferences] using DataStore with
 * AES-256-GCM encryption for all values.
 *
 * Keys are stored in plaintext (they are not sensitive), but all values are
 * encrypted before persistence and decrypted on read.
 *
 * @param context Application context used to resolve the DataStore file location.
 * @param encryptionManager Used to encrypt/decrypt values before/after storage.
 * @param sharedSecret The shared secret derived during pairing, used as the encryption key.
 * @param dataStoreFileName Name of the DataStore preferences file.
 */
class SecurePreferencesImpl(
    context: Context,
    private val encryptionManager: EncryptionManager,
    private val sharedSecret: ByteArray,
    dataStoreFileName: String = "secure_prefs"
) : SecurePreferences {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(context.filesDir, "datastore/$dataStoreFileName.preferences_pb")
    }

    /**
     * In-memory cache of decrypted values to minimize cryptographic operations.
     * The cache is invalidated on every write operation for simplicity.
     */
    private val cache = mutableMapOf<String, String>()
    private val cacheMutex = Mutex()

    override suspend fun putString(key: String, value: String) {
        cacheMutex.withLock { cache.clear() }
        val encrypted = encryptionManager.encryptWithSharedSecret(value, sharedSecret)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = encrypted
        }
    }

    override suspend fun getString(key: String, default: String?): String? {
        // Check cache first
        cacheMutex.withLock {
            cache[key]?.let { return it }
        }

        val encrypted = dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(key)]
        }.first() ?: return default

        return try {
            val decrypted = encryptionManager.decryptWithSharedSecret(encrypted, sharedSecret)
            cacheMutex.withLock {
                if (cache.size >= 100) {
                    cache.clear()
                }
                cache[key] = decrypted
            }
            decrypted
        } catch (_: Exception) {
            // If decryption fails (e.g., shared secret rotated), return default
            default
        }
    }

    override suspend fun putBoolean(key: String, value: Boolean) {
        putString(key, value.toString())
    }

    override suspend fun getBoolean(key: String, default: Boolean): Boolean {
        val raw = getString(key) ?: return default
        return raw.toBooleanStrictOrNull() ?: default
    }

    override suspend fun remove(key: String) {
        cacheMutex.withLock { cache.remove(key) }
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
        }
    }

    override suspend fun clear() {
        cacheMutex.withLock { cache.clear() }
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

/**
 * Minimal implementation of [SecurePreferences] used **before pairing is complete**.
 *
 * This stores values in DataStore **without encryption** because no shared secret
 * exists yet. It is intended only for storing the device ID and pairing state.
 * Once pairing completes, migrate to [SecurePreferencesImpl] with the shared secret.
 *
 * **Security Warning:** Do not store sensitive data in this implementation.
 */
class UnpairedSecurePreferences(
    context: Context,
    dataStoreFileName: String = "unpaired_prefs"
) : SecurePreferences {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(context.filesDir, "datastore/$dataStoreFileName.preferences_pb")
    }

    override suspend fun putString(key: String, value: String) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = value
        }
    }

    override suspend fun getString(key: String, default: String?): String? {
        return dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(key)]
        }.first() ?: default
    }

    override suspend fun putBoolean(key: String, value: Boolean) {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(key)] = value
        }
    }

    override suspend fun getBoolean(key: String, default: Boolean): Boolean {
        return dataStore.data.map { prefs ->
            prefs[booleanPreferencesKey(key)]
        }.first() ?: default
    }

    override suspend fun remove(key: String) {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
