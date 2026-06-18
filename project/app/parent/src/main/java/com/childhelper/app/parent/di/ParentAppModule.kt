package com.childhelper.app.parent.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.childhelper.app.parent.db.AlertDao
import com.childhelper.app.parent.db.AppDatabase
import com.childhelper.app.parent.repository.AlertHistoryRepository
import com.childhelper.core.security.SecurePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "parent_settings")

/**
 * Qualifier for the application-level coroutine scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * Hilt DI module for the parent app.
 * Provides database (with SQLCipher encryption), repository, DataStore, and app-scoped dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object ParentAppModule {

    /**
     * Provides the SQLCipher database passphrase.
     *
     * Generates a fresh cryptographically-secure passphrase for each install,
     * then stores it asynchronously. On subsequent starts, retrieves the
     * stored passphrase. Uses [runBlocking] ONLY on first call (when no
     * passphrase exists yet); on subsequent calls the in-memory cache returns
     * immediately without blocking.
     */
    @Provides
    @Singleton
    fun provideDatabasePassphrase(
        securePreferences: SecurePreferences
    ): ByteArray {
        // Try in-memory cache first (fast path, no blocking)
        cachedPassphrase?.let { return it }

        return try {
            runBlocking {
                val stored = securePreferences.getString(PREF_KEY_DB_PASSPHRASE)
                if (stored != null) {
                    val key = stored.toByteArray(Charsets.UTF_8)
                    cachedPassphrase = key
                    key
                } else {
                    val newKey = AppDatabase.generatePassphrase()
                    securePreferences.putString(
                        PREF_KEY_DB_PASSPHRASE,
                        String(newKey, Charsets.UTF_8)
                    )
                    cachedPassphrase = newKey
                    newKey
                }
            }
        } catch (e: Exception) {
            cachedPassphrase ?: AppDatabase.generatePassphrase().also {
                cachedPassphrase = it
            }
        }
    }

    @Volatile
    private var cachedPassphrase: ByteArray? = null

    private const val PREF_KEY_DB_PASSPHRASE = "db_passphrase"

    /**
     * Provides the Room database instance with SQLCipher encryption.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        passphrase: ByteArray
    ): AppDatabase {
        return AppDatabase.create(context, passphrase)
    }

    /**
     * Provides the AlertDao from the database.
     */
    @Provides
    @Singleton
    fun provideAlertDao(database: AppDatabase): AlertDao {
        return database.alertDao()
    }

    /**
     * Provides the AlertHistoryRepository.
     */
    @Provides
    @Singleton
    fun provideAlertHistoryRepository(
        alertDao: AlertDao,
        preferences: SecurePreferences
    ): AlertHistoryRepository {
        return AlertHistoryRepository(alertDao, preferences)
    }

    /**
     * Provides the DataStore for app settings.
     */
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.settingsDataStore
    }

    /**
     * Provides an application-scoped CoroutineScope for background operations
     * (e.g., retention policy enforcement, WebRTC management).
     */
    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

}
