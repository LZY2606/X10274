package app

import java.sql.Connection
import java.sql.DriverManager

object Db {
    /** Versioned schema migrations; applied in order, recorded in schema_migrations. */
    val migrations: List<Pair<Int, String>> = listOf(
        1 to """CREATE TABLE schema_migrations(
            version INTEGER PRIMARY KEY,
            applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )""",
        2 to """CREATE TABLE meta(
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )""",
        3 to """CREATE TABLE batch(
            id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            status TEXT NOT NULL,
            error TEXT,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )""",
        4 to """CREATE TABLE instance(
            id TEXT PRIMARY KEY,
            batch_id TEXT NOT NULL REFERENCES batch(id),
            seq INTEGER NOT NULL,
            transfer_syntax TEXT NOT NULL,
            raw_tags TEXT NOT NULL,
            pixel_b64 TEXT,
            source_ref TEXT NOT NULL,
            supersedes TEXT
        )""",
        5 to """CREATE TABLE derived(
            instance_id TEXT NOT NULL REFERENCES instance(id),
            rule_version TEXT NOT NULL,
            payload TEXT NOT NULL,
            PRIMARY KEY (instance_id, rule_version)
        )""",
        6 to """CREATE TABLE branch(
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            parent_id TEXT,
            head_version INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )""",
        7 to """CREATE TABLE event(
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            branch_id TEXT,
            kind TEXT NOT NULL,
            payload TEXT NOT NULL,
            rule_version TEXT,
            param_version INTEGER,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )""",
        8 to """CREATE TABLE candidate(
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL REFERENCES branch(id),
            kind TEXT NOT NULL,
            series_uid TEXT NOT NULL,
            frame_of_reference_uid TEXT,
            members TEXT NOT NULL,
            projection_mm TEXT NOT NULL,
            spacing_residuals_mm TEXT NOT NULL,
            normal_lps TEXT,
            diagnostics TEXT NOT NULL,
            rule_version TEXT NOT NULL,
            param_version INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'PROPOSED',
            event_seq INTEGER NOT NULL,
            superseded_event_seq INTEGER
        )""",
        9 to """CREATE TABLE candidate_dep(
            candidate_id TEXT NOT NULL REFERENCES candidate(id),
            instance_id TEXT NOT NULL,
            param_key TEXT NOT NULL,
            PRIMARY KEY (candidate_id, instance_id, param_key)
        )""",
        10 to """CREATE TABLE adjudication(
            id TEXT PRIMARY KEY,
            branch_id TEXT NOT NULL REFERENCES branch(id),
            candidate_id TEXT NOT NULL,
            decision TEXT NOT NULL,
            rationale TEXT,
            base_version INTEGER NOT NULL,
            event_seq INTEGER NOT NULL,
            undone_event_seq INTEGER
        )"""
    )

    fun connect(path: String): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        return conn
    }

    fun migrate(conn: Connection) {
        val hasMigrationsTable = conn.metaData.getTables(null, null, "schema_migrations", null).use { it.next() }
        if (!hasMigrationsTable) {
            conn.createStatement().use { it.execute(migrations.first().second) }
        }
        val applied = mutableSetOf<Int>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_migrations").use { rs ->
                while (rs.next()) applied += rs.getInt(1)
            }
        }
        migrations.forEach { (v, sql) ->
            if (v !in applied) {
                conn.createStatement().use { it.execute(sql) }
                conn.prepareStatement("INSERT INTO schema_migrations(version) VALUES (?)").use { ps ->
                    ps.setInt(1, v); ps.executeUpdate()
                }
            }
        }
    }
}
