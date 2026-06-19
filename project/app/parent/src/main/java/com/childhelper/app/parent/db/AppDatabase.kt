package com.childhelper.app.parent.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

/**
 * Room database for the parent app, encrypted with SQLCipher.
 * Stores alert history metadata only — no media content.
 */
@Database(
    entities = [AlertEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun alertDao(): AlertDao

    companion object {
        private const val DATABASE_NAME = "parent_alerts.db"

        /**
         * Creates a SQLCipher-encrypted Room database instance.
         * The passphrase is retrieved from the KeystoreManager via DI.
         */
        fun create(
            context: Context,
            passphrase: ByteArray
        ): AppDatabase {
            val factory = SupportFactory(passphrase)
            val dbFile = context.getDatabasePath(DATABASE_NAME)

            return try {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()
            } catch (e: Exception) {
                // Database corrupted or passphrase changed — delete and recreate
                dbFile.delete()
                dbFile.parentFile?.listFiles()?.filter {
                    it.name.startsWith(DATABASE_NAME)
                }?.forEach { it.delete() }

                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()
            }
        }

        /**
         * Generate a cryptographically secure random passphrase.
         * Should be stored in Android Keystore and retrieved at runtime.
         */
        fun generatePassphrase(): ByteArray {
            val random = SecureRandom()
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return bytes
        }
    }
}
