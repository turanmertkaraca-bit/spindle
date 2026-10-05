package dev.spindle.store.sqlite

import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import dev.spindle.core.store.SnapshotStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * A durable [SnapshotStore] on SQLite, mirroring [SqliteSessionStore]'s
 * connection, migration and locking conventions. Snapshots are stored whole so
 * a deleted file can be restored.
 *
 * Uses its own `snapshot_schema_version` counter rather than the session
 * store's `schema_version`: the two stores evolve independently and may share
 * a database file, so versioning them together would let one store skip the
 * other's migrations.
 */
class SqliteSnapshotStore(dbPath: Path) : SnapshotStore, AutoCloseable {

    private val mutex = Mutex()
    private val connection: Connection

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        migrate()
    }

    private fun migrate() {
        connection.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS snapshot_schema_version (version INTEGER NOT NULL)")
        }
        val current = connection.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM snapshot_schema_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        for (migration in SNAPSHOT_MIGRATIONS) {
            if (migration.version <= current) continue
            connection.autoCommit = false
            try {
                migration.apply(connection)
                connection.prepareStatement("INSERT INTO snapshot_schema_version(version) VALUES (?)").use {
                    it.setInt(1, migration.version)
                    it.executeUpdate()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun record(snapshot: Snapshot) {
        mutex.withLock {
            connection.prepareStatement(
                "INSERT OR REPLACE INTO snapshots(id, session_id, path, content, sha256, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, snapshot.id)
                st.setString(2, snapshot.sessionId.value)
                st.setString(3, snapshot.path)
                st.setString(4, snapshot.content)
                st.setString(5, snapshot.sha256)
                st.setLong(6, snapshot.createdAt)
                st.executeUpdate()
            }
        }
    }

    override suspend fun latest(sessionId: SessionId, path: String): Snapshot? = mutex.withLock {
        connection.prepareStatement(
            "SELECT * FROM snapshots WHERE session_id = ? AND path = ? " +
                "ORDER BY created_at DESC, rowid DESC LIMIT 1",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.setString(2, path)
            st.executeQuery().use { rs -> if (rs.next()) readSnapshot(rs) else null }
        }
    }

    override suspend fun forSession(sessionId: SessionId): List<Snapshot> = mutex.withLock {
        connection.prepareStatement(
            "SELECT * FROM snapshots WHERE session_id = ? ORDER BY created_at ASC, rowid ASC",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(readSnapshot(rs)) } }
        }
    }

    override suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM snapshots WHERE id = ?").use { st ->
                    for (id in ids) {
                        st.setString(1, id)
                        st.addBatch()
                    }
                    st.executeBatch()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun prune(keep: Int): Int = mutex.withLock {
        connection.prepareStatement(
            "DELETE FROM snapshots WHERE rowid IN (" +
                "SELECT s.rowid FROM snapshots s WHERE (" +
                "SELECT COUNT(*) FROM snapshots x WHERE x.session_id = s.session_id " +
                "AND (x.created_at > s.created_at OR (x.created_at = s.created_at AND x.rowid > s.rowid))" +
                ") >= ?)",
        ).use { st ->
            st.setInt(1, keep.coerceAtLeast(0))
            st.executeUpdate()
        }
    }

    /**
     * Bounded retention across every session. Age is applied first, then the
     * per-session cap, then the global cap, all in one transaction so whole-file
     * snapshots cannot grow forever — including the snapshots of sessions that
     * have since been deleted (which [SqliteSessionStore.prune] does not touch).
     *
     * A negative [keepPerSession] or [maxTotal] means "drop everything", matching
     * the in-memory and Android implementations.
     */
    override suspend fun pruneBounded(
        keepPerSession: Int,
        maxTotal: Int,
        maxAgeMillis: Long,
    ): Int = mutex.withLock {
        var removed = 0
        connection.autoCommit = false
        try {
            if (maxAgeMillis > 0) {
                val cutoff = System.currentTimeMillis() - maxAgeMillis
                removed += connection.prepareStatement("DELETE FROM snapshots WHERE created_at < ?").use {
                    it.setLong(1, cutoff); it.executeUpdate()
                }
            }
            if (keepPerSession >= 0) {
                removed += connection.prepareStatement(
                    "DELETE FROM snapshots WHERE rowid IN (" +
                        "SELECT s.rowid FROM snapshots s WHERE (" +
                        "SELECT COUNT(*) FROM snapshots x WHERE x.session_id = s.session_id " +
                        "AND (x.created_at > s.created_at OR (x.created_at = s.created_at AND x.rowid > s.rowid))" +
                        ") >= ?)",
                ).use {
                    it.setInt(1, keepPerSession); it.executeUpdate()
                }
            } else {
                removed += connection.prepareStatement("DELETE FROM snapshots").use { it.executeUpdate() }
            }
            if (maxTotal >= 0) {
                val count = connection.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM snapshots").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
                val excess = count - maxTotal
                if (excess > 0) {
                    removed += connection.prepareStatement(
                        "DELETE FROM snapshots WHERE rowid IN (" +
                            "SELECT rowid FROM snapshots ORDER BY created_at ASC, rowid ASC LIMIT ?)",
                    ).use {
                        it.setInt(1, excess); it.executeUpdate()
                    }
                }
            } else {
                removed += connection.prepareStatement("DELETE FROM snapshots").use { it.executeUpdate() }
            }
            connection.commit()
            removed
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    private fun readSnapshot(rs: ResultSet) = Snapshot(
        id = rs.getString("id"),
        sessionId = SessionId(rs.getString("session_id")),
        path = rs.getString("path"),
        content = rs.getString("content"),
        sha256 = rs.getString("sha256"),
        createdAt = rs.getLong("created_at"),
    )

    override fun close() {
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        connection.close()
    }

    companion object {
        fun open(dbFile: Path): SqliteSnapshotStore = SqliteSnapshotStore(dbFile)
    }
}

private class SnapshotMigration(val version: Int, val apply: (Connection) -> Unit)

private val SNAPSHOT_MIGRATIONS = listOf(
    SnapshotMigration(1) { connection ->
        connection.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS snapshots (" +
                    "id TEXT PRIMARY KEY, " +
                    "session_id TEXT NOT NULL, " +
                    "path TEXT NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "sha256 TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL)",
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_session_path ON snapshots(session_id, path)")
        }
    },
    SnapshotMigration(2) { connection ->
        // Converge on the plural table name the Android store already uses so
        // both stores can share one file. Only a legacy singular table is
        // renamed (and only when the plural one does not already exist).
        connection.createStatement().use { st ->
            val tables = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
            if ("snapshot" in tables && "snapshots" !in tables) {
                st.execute("ALTER TABLE snapshot RENAME TO snapshots")
            }
        }
    },
)
