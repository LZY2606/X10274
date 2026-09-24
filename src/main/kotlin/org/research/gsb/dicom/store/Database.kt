package org.research.gsb.dicom.store

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** SQLite schema is created/upgraded through numbered, tested migrations. */
class Database(private val path: String) {
    fun connect(): Connection {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.autoCommit = false
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("PRAGMA journal_mode = WAL")
        }
        conn.commit()
        migrate(conn)
        return conn
    }

    private fun migrate(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS schema_migrations (
                     version INTEGER PRIMARY KEY,
                     applied_at TEXT NOT NULL DEFAULT (datetime('now'))
                   )"""
            )
        }
        val applied = mutableSetOf<Int>()
        conn.prepareStatement("SELECT version FROM schema_migrations").use { rs ->
            while (rs.next()) applied += rs.getInt(1)
        }
        for (m in MIGRATIONS) {
            if (m.version !in applied) {
                conn.createStatement().use { st -> m.up(st) }
                conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (?)").use { ps ->
                    ps.setInt(1, m.version); ps.executeUpdate()
                }
                conn.commit()
            }
        }
    }

    companion object {
        val MIGRATIONS = listOf(
            Migration(1) { st ->
                st.execute(
                    """CREATE TABLE branches (
                         branch_id TEXT PRIMARY KEY,
                         parent_branch_id TEXT,
                         name TEXT NOT NULL,
                         created_at TEXT NOT NULL,
                         head_event_id TEXT
                       )"""
                )
                st.execute(
                    """CREATE TABLE events (
                         event_id TEXT PRIMARY KEY,
                         branch_id TEXT NOT NULL,
                         parent_event_id TEXT,
                         seq INTEGER NOT NULL,
                         actor TEXT NOT NULL,
                         created_at TEXT NOT NULL,
                         rule_version_id TEXT NOT NULL,
                         type TEXT NOT NULL,
                         payload_json TEXT NOT NULL,
                         sha256 TEXT NOT NULL,
                         FOREIGN KEY(branch_id) REFERENCES branches(branch_id)
                       )"""
                )
                st.execute(
                    """CREATE TABLE blobs (
                         sha256 TEXT PRIMARY KEY,
                         kind TEXT NOT NULL,
                         bytes BLOB NOT NULL,
                         size INTEGER NOT NULL
                       )"""
                )
                st.execute(
                    """CREATE TABLE source_slots (
                         slot_id TEXT PRIMARY KEY,
                         relative_path TEXT NOT NULL,
                         current_sha256 TEXT NOT NULL,
                         first_event_id TEXT NOT NULL
                       )"""
                )
                st.execute("CREATE INDEX idx_events_branch ON events(branch_id, seq)")
            },
            Migration(2) { st ->
                // Audit of derived conclusions superseded by an event (invalidation ledger).
                st.execute(
                    """CREATE TABLE invalidations (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         event_id TEXT NOT NULL,
                         kind TEXT NOT NULL,
                         entity_id TEXT NOT NULL,
                         reason TEXT NOT NULL,
                         rule_version_id TEXT NOT NULL
                       )"""
                )
                st.execute("CREATE INDEX idx_invalidations_event ON invalidations(event_id)")
            },
            Migration(3) { st ->
                // Stable label for the bundle a slot belongs to (re-import identity anchor).
                st.execute("ALTER TABLE source_slots ADD COLUMN stable_key TEXT")
                st.execute("UPDATE source_slots SET stable_key = slot_id")
            },
        )
    }
}

data class Migration(val version: Int, val up: (Statement) -> Unit)
