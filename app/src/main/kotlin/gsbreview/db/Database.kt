package gsbreview.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

object Database {
    fun open(dbPath: Path): Connection {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA busy_timeout = 5000")
        }
        migrate(conn)
        return conn
    }

    fun migrate(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS schema_version (
                  | version INTEGER PRIMARY KEY,
                  | applied_at TEXT NOT NULL DEFAULT (datetime('now'))
                  |)""".trimMargin()
            )
        }
        val applied = mutableSetOf<Int>()
        conn.createStatement().executeQuery("SELECT version FROM schema_version").use { rs ->
            while (rs.next()) applied += rs.getInt(1)
        }
        val migrations = listOf(1 to "db/V1__initial.sql", 2 to "db/V2__case_lineage.sql")
        for ((version, resource) in migrations) {
            if (version in applied) continue
            val sql = Database::class.java.classLoader.getResourceAsStream(resource)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("missing migration resource $resource")
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(sql) }
                conn.prepareStatement("INSERT INTO schema_version(version) VALUES (?)").use { ps ->
                    ps.setInt(1, version); ps.executeUpdate()
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
