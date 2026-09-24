package dicomlab

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * SQLite persistence. Raw source rows (imports/instances) are immutable once written;
 * everything derived lives in interpretation branches keyed by content-stable ids.
 */
class Db(val path: String) {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also { c ->
        c.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }

    fun <T> tx(block: (Connection) -> T): T {
        val prev = connection.autoCommit
        connection.autoCommit = false
        try {
            val r = block(connection)
            connection.commit()
            return r
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = prev
        }
    }

    /** Ordered migrations; each runs inside its own transaction. */
    fun migrate(target: Int = MIGRATIONS.size) {
        val applied = appliedVersions()
        for (v in (applied + 1)..target) {
            tx { c ->
                c.createStatement().use { st ->
                    MIGRATIONS[v - 1].split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
                }
                c.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES(?,?)").use { ps ->
                    ps.setInt(1, v); ps.setString(2, Instant.now().toString()); ps.executeUpdate()
                }
            }
        }
    }

    fun appliedVersions(): Int {
        val exists = connection.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_migrations'").next()
        }
        if (!exists) return 0
        return connection.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")
            rs.next(); rs.getInt(1)
        }
    }

    companion object {
        val MIGRATIONS: List<String> = listOf(
            // v1: core model
            """
            CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
            CREATE TABLE imports(
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              source_desc TEXT NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE instances(
              id TEXT PRIMARY KEY,
              import_id TEXT NOT NULL REFERENCES imports(id),
              slice_key TEXT NOT NULL,
              parent_instance_id TEXT,
              sop_instance_uid TEXT,
              series_instance_uid TEXT,
              frame_of_reference_uid TEXT,
              transfer_syntax_uid TEXT,
              instance_number INTEGER,
              image_position_patient TEXT,
              image_orientation_patient TEXT,
              pixel_spacing TEXT,
              multiframe_json TEXT,
              raw_tags_json TEXT NOT NULL
            );
            CREATE TABLE rule_sets(
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              version INTEGER NOT NULL,
              params_json TEXT NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE interpretations(
              id TEXT PRIMARY KEY,
              import_id TEXT NOT NULL REFERENCES imports(id),
              rule_set_id TEXT NOT NULL REFERENCES rule_sets(id),
              parent_id TEXT,
              label TEXT NOT NULL,
              created_at TEXT NOT NULL,
              version INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE candidates(
              id TEXT PRIMARY KEY,
              interpretation_id TEXT NOT NULL REFERENCES interpretations(id),
              kind TEXT NOT NULL,
              branch_key TEXT NOT NULL,
              label TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'proposed',
              sort_index INTEGER NOT NULL
            );
            CREATE TABLE candidate_members(
              candidate_id TEXT NOT NULL REFERENCES candidates(id),
              instance_id TEXT NOT NULL REFERENCES instances(id),
              projection REAL,
              spacing_residual REAL,
              sort_index INTEGER NOT NULL,
              PRIMARY KEY(candidate_id, instance_id)
            );
            CREATE TABLE diagnostics(
              id TEXT PRIMARY KEY,
              interpretation_id TEXT NOT NULL REFERENCES interpretations(id),
              instance_id TEXT,
              code TEXT NOT NULL,
              message TEXT NOT NULL,
              detail_json TEXT NOT NULL
            );
            CREATE TABLE events(
              id TEXT PRIMARY KEY,
              interpretation_id TEXT NOT NULL REFERENCES interpretations(id),
              seq INTEGER NOT NULL,
              type TEXT NOT NULL,
              actor TEXT NOT NULL,
              payload_json TEXT NOT NULL,
              snapshot_json TEXT NOT NULL,
              created_at TEXT NOT NULL,
              UNIQUE(interpretation_id, seq)
            );
            """,
            // v2: review notes on interpretations + lookup index (kept small on purpose so
            // migration tests can verify stepwise application and data preservation).
            """
            ALTER TABLE interpretations ADD COLUMN note TEXT NOT NULL DEFAULT '';
            CREATE INDEX idx_events_interp ON events(interpretation_id, seq);
            CREATE INDEX idx_candidates_interp ON candidates(interpretation_id);
            """
        )
    }
}
