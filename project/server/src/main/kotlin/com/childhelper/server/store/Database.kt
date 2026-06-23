package com.childhelper.server.store

import java.sql.Connection
import java.sql.DriverManager
import java.io.File

object Database {
    private const val DB_FILE = "childhelper.db"
    @Volatile private var connection: Connection? = null

    fun getConnection(): Connection {
        connection?.let { if (!it.isClosed) return it }
        synchronized(this) {
            connection?.let { if (!it.isClosed) return it }
            val dbPath = File(System.getProperty("user.dir", "."), DB_FILE).absolutePath
            val newConn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            newConn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA foreign_keys=ON")
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pairing_sessions (
                        session_id TEXT PRIMARY KEY,
                        pairing_code TEXT NOT NULL,
                        child_device_id TEXT NOT NULL,
                        parent_device_id TEXT,
                        child_public_key TEXT NOT NULL,
                        parent_public_key TEXT,
                        parent_phone_number TEXT,
                        parent_display_name TEXT,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS signaling_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_device_id TEXT NOT NULL,
                        message_type TEXT NOT NULL,
                        message_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """)
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_signaling_target ON signaling_messages(target_device_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pairing_code ON pairing_sessions(pairing_code)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pairing_child ON pairing_sessions(child_device_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pairing_parent ON pairing_sessions(parent_device_id)")
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pending_alerts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        parent_device_id TEXT NOT NULL,
                        child_device_id TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        delivered INTEGER NOT NULL DEFAULT 0
                    )
                """)
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_alerts_parent ON pending_alerts(parent_device_id)")
            }
            connection = newConn
            return newConn
        }
    }

    fun closeConnection() {
        synchronized(this) {
            connection?.close()
            connection = null
        }
    }
}
