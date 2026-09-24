package gsb.db

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

object Migrations {
    /** 分步迁移：V1 事件与原始材料；V2 派生层与候选；V3 裁定与依赖。 */
    val STEPS: List<Pair<Int, List<String>>> = listOf(
        1 to listOf(
            """CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
            """CREATE TABLE events(
                 seq INTEGER PRIMARY KEY AUTOINCREMENT,
                 id TEXT NOT NULL UNIQUE,
                 type TEXT NOT NULL,
                 payload TEXT NOT NULL,
                 rule_version INTEGER NOT NULL,
                 param_version INTEGER NOT NULL,
                 created_at TEXT NOT NULL)""",
            """CREATE TABLE instances(
                 id TEXT PRIMARY KEY,
                 file_name TEXT NOT NULL,
                 transfer_syntax TEXT NOT NULL,
                 pixel_b64 TEXT NOT NULL,
                 tags_json TEXT NOT NULL,
                 content_hash TEXT NOT NULL,
                 stage TEXT NOT NULL,
                 amended_by TEXT,
                 imported_seq INTEGER NOT NULL)""",
        ),
        2 to listOf(
            """CREATE TABLE frames(
                 id TEXT PRIMARY KEY,
                 instance_id TEXT NOT NULL,
                 frame_index INTEGER NOT NULL,
                 geom_json TEXT NOT NULL,
                 derived_seq INTEGER NOT NULL)""",
            """CREATE TABLE params(
                 version INTEGER PRIMARY KEY,
                 json TEXT NOT NULL,
                 created_seq INTEGER NOT NULL)""",
            """CREATE TABLE candidate_sets(
                 id TEXT PRIMARY KEY,
                 partition_key TEXT NOT NULL,
                 rule_version INTEGER NOT NULL,
                 param_version INTEGER NOT NULL,
                 json TEXT NOT NULL,
                 created_seq INTEGER NOT NULL,
                 superseded_seq INTEGER)""",
        ),
        3 to listOf(
            """CREATE TABLE adjudications(
                 id TEXT PRIMARY KEY,
                 candidate_id TEXT NOT NULL,
                 decision TEXT NOT NULL,
                 comment TEXT NOT NULL,
                 version INTEGER NOT NULL,
                 created_seq INTEGER NOT NULL,
                 undone_seq INTEGER)""",
            """CREATE TABLE dependencies(
                 candidate_set_id TEXT NOT NULL,
                 instance_id TEXT NOT NULL,
                 PRIMARY KEY(candidate_set_id, instance_id))""",
            """CREATE INDEX idx_frames_instance ON frames(instance_id)""",
            """CREATE INDEX idx_sets_partition ON candidate_sets(partition_key)""",
        ),
    )
    val LATEST: Int get() = STEPS.maxOf { it.first }
}

class Database(path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        autoCommit = false
        createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }

    fun <T> tx(block: () -> T): T {
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }

    fun migrateTo(target: Int) {
        tx {
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)")
            }
            val current = conn.createStatement()
                .executeQuery("SELECT COALESCE(MAX(version),0) AS v FROM schema_migrations")
                .also { it.next() }.getInt("v")
            for ((v, statements) in Migrations.STEPS) {
                if (v in (current + 1)..target) {
                    conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
                    conn.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES (?,?)").use { ps ->
                        ps.setInt(1, v)
                        ps.setString(2, Instant.now().toString())
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    fun migrate() = migrateTo(Migrations.LATEST)

    fun migrationVersions(): List<Int> =
        conn.createStatement().executeQuery("SELECT version FROM schema_migrations ORDER BY version")
            .let { rs -> generateSequence { if (rs.next()) rs.getInt(1) else null }.toList() }

    fun close() = conn.close()
}
