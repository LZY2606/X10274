package gsb.store

import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 存储。schema 以递增版本迁移：
 *  v1 初始结构；v2 为 decisions 增加 undone 列（裁定可撤销）。
 */
class Db(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("PRAGMA journal_mode = WAL")
        }
    }

    fun migrate() {
        val current = schemaVersion()
        for ((version, sql) in MIGRATIONS) {
            if (version > current) {
                conn.autoCommit = false
                try {
                    conn.createStatement().use { st ->
                        for (stmt in sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                            st.execute(stmt)
                        }
                        st.execute("INSERT INTO meta(key, value) VALUES('schema_version', '$version') " +
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value")
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }
    }

    fun schemaVersion(): Int {
        conn.createStatement().use { st ->
            val tables = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")
            if (!tables.next()) return 0
            val rs = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")
            return if (rs.next()) rs.getString(1).toInt() else 0
        }
    }

    override fun close() = conn.close()

    companion object {
        /** v1 建表语句单独暴露，迁移测试用它构造旧版本数据库。 */
        val V1_SCHEMA = """
            CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE batches(
                id TEXT PRIMARY KEY, label TEXT NOT NULL, status TEXT NOT NULL,
                error TEXT, created_at TEXT NOT NULL);
            CREATE TABLE sources(
                id TEXT PRIMARY KEY, batch_id TEXT NOT NULL, name TEXT NOT NULL,
                hash TEXT NOT NULL, transfer_syntax TEXT NOT NULL, supported INTEGER NOT NULL,
                raw BLOB NOT NULL, tags_json TEXT NOT NULL, supersedes TEXT, created_at TEXT NOT NULL);
            CREATE TABLE branches(
                id TEXT PRIMARY KEY, parent_id TEXT, name TEXT NOT NULL,
                params_json TEXT NOT NULL, params_version INTEGER NOT NULL,
                head_version INTEGER NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL);
            CREATE TABLE candidates(
                id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, seq INTEGER NOT NULL,
                kind TEXT NOT NULL, status TEXT NOT NULL,
                reason_json TEXT NOT NULL, dep_json TEXT NOT NULL);
            CREATE TABLE slices(
                id TEXT PRIMARY KEY, candidate_id TEXT NOT NULL, ord INTEGER NOT NULL,
                source_id TEXT NOT NULL, frame_index INTEGER NOT NULL,
                proj REAL, residual REAL, plane_json TEXT, diag_json TEXT NOT NULL);
            CREATE TABLE decisions(
                id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, version INTEGER NOT NULL,
                action TEXT NOT NULL, target_kind TEXT NOT NULL, target_id TEXT NOT NULL,
                payload_json TEXT NOT NULL, created_at TEXT NOT NULL);
            CREATE TABLE events(
                id TEXT PRIMARY KEY, branch_id TEXT, version INTEGER,
                type TEXT NOT NULL, payload_json TEXT NOT NULL,
                snapshot_json TEXT, created_at TEXT NOT NULL)
        """

        val MIGRATIONS: List<Pair<Int, String>> = listOf(
            1 to V1_SCHEMA,
            2 to "ALTER TABLE decisions ADD COLUMN undone INTEGER NOT NULL DEFAULT 0",
        )
    }
}
