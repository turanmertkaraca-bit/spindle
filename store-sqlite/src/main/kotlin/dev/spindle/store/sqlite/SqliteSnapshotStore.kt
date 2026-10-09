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
            // Content-addressed: the body is written once per distinct sha256 and
            // the row references it. A blank sha falls back to the row id so the
            // content stays reachable.
            val sha = snapshot.sha256.ifBlank { snapshot.id }
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT OR IGNORE INTO snapshot_blobs(sha256, content) VALUES (?, ?)",
                ).use { st ->
                    st.setString(1, sha)
                    st.setString(2, snapshot.content)
                    st.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT OR REPLACE INTO snapshots(id, session_id, path, sha256, created_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                ).use { st ->
                    st.setString(1, snapshot.id)
                    st.setString(2, snapshot.sessionId.value)
                    st.setString(3, snapshot.path)
                    st.setString(4, sha)
                    st.setLong(5, snapshot.createdAt)
                    st.executeUpdate()
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

    override suspend fun latest(sessionId: SessionId, path: String): Snapshot? = mutex.withLock {
        connection.prepareStatement(
            "SELECT s.id, s.session_id, s.path, s.sha256, s.created_at, b.content " +
                "FROM snapshots s LEFT JOIN snapshot_blobs b ON b.sha256 = s.sha256 " +
                "WHERE s.session_id = ? AND s.path = ? " +
                "ORDER BY s.created_at DESC, s.rowid DESC LIMIT 1",
        ).use { st ->
            st.setString(1, sessionId.value)
            st.setString(2, path)
            st.executeQuery().use { rs -> if (rs.next()) readSnapshot(rs) else null }
        }
    }

    override suspend fun forSession(sessionId: SessionId): List<Snapshot> = mutex.withLock {
        connection.prepareStatement(
            "SELECT s.id, s.session_id, s.path, s.sha256, s.created_at, b.content " +
                "FROM snapshots s LEFT JOIN snapshot_blobs b ON b.sha256 = s.sha256 " +
                "WHERE s.session_id = ? ORDER BY s.created_at ASC, s.rowid ASC",
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
                gcBlobs()
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
        connection.autoCommit = false
        try {
            val removed = connection.prepareStatement(
                "DELETE FROM snapshots WHERE rowid IN (" +
                    "SELECT s.rowid FROM snapshots s WHERE (" +
                    "SELECT COUNT(*) FROM snapshots x WHERE x.session_id = s.session_id " +
                    "AND (x.created_at > s.created_at OR (x.created_at = s.created_at AND x.rowid > s.rowid))" +
                    ") >= ?)",
            ).use { st ->
                st.setInt(1, keep.coerceAtLeast(0))
                st.executeUpdate()
            }
            gcBlobs()
            connection.commit()
            removed
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Bounded retention across every session. Age is applied first, then the
     * per-session cap, then the global cap, all in one transaction so whole-file
     * snapshots cannot grow forever — including the snapshots of sessions that
     * have since been deleted (which [SqliteSessionStore.prune] does not touch).
     *
     * The newest snapshot of every `(session, path)` is pinned — it survives the
     * age, per-session and global caps — so a per-file revert always has a
     * pre-image. A negative [keepPerSession] or [maxTotal] means "drop
     * everything", pins included, matching the in-memory and Android
     * implementations.
     */
    override suspend fun pruneBounded(
        keepPerSession: Int,
        maxTotal: Int,
        maxAgeMillis: Long,
    ): Int = mutex.withLock {
        var removed = 0
        connection.autoCommit = false
        try {
            if (keepPerSession < 0 || maxTotal < 0) {
                removed += connection.prepareStatement("DELETE FROM snapshots").use { it.executeUpdate() }
            } else {
                if (maxAgeMillis > 0) {
                    val cutoff = System.currentTimeMillis() - maxAgeMillis
                    removed += connection.prepareStatement(
                        "DELETE FROM snapshots WHERE created_at < ? AND id NOT IN $PINNED_IDS",
                    ).use {
                        it.setLong(1, cutoff); it.executeUpdate()
                    }
                }
                // Remove the oldest non-pinned rows beyond the per-session cap,
                // mirroring the in-memory and Android stores exactly (pins may
                // leave a session over its budget).
                val pinned = pinnedIdSet()
                val sessions = connection.createStatement().use { st ->
                    st.executeQuery("SELECT DISTINCT session_id FROM snapshots").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
                for (sid in sessions) {
                    val ids = connection.prepareStatement(
                        "SELECT id FROM snapshots WHERE session_id = ? ORDER BY created_at DESC, rowid DESC",
                    ).use { st ->
                        st.setString(1, sid)
                        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                    }
                    var toRemove = (ids.size - keepPerSession).coerceAtLeast(0)
                    for (i in ids.indices.reversed()) {
                        if (toRemove == 0) break
                        val id = ids[i]
                        if (id in pinned) continue
                        removed += connection.prepareStatement("DELETE FROM snapshots WHERE id = ?").use {
                            it.setString(1, id); it.executeUpdate()
                        }
                        toRemove--
                    }
                }
                val count = connection.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM snapshots").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
                val excess = count - maxTotal
                if (excess > 0) {
                    removed += connection.prepareStatement(
                        "DELETE FROM snapshots WHERE rowid IN (" +
                            "SELECT rowid FROM snapshots WHERE id NOT IN $PINNED_IDS " +
                            "ORDER BY created_at ASC, rowid ASC LIMIT ?)",
                    ).use {
                        it.setInt(1, excess); it.executeUpdate()
                    }
                }
            }
            gcBlobs()
            connection.commit()
            removed
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    /** The newest snapshot id of every `(session, path)` pair. */
    private fun pinnedIdSet(): Set<String> =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT id FROM $PINNED_IDS").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    /**
     * Drop blobs no snapshot row references any more. Shared content survives as
     * long as a single referencing row remains. Assumes the caller holds [mutex].
     */
    private fun gcBlobs() {
        connection.prepareStatement(
            "DELETE FROM snapshot_blobs " +
                "WHERE sha256 NOT IN (SELECT sha256 FROM snapshots WHERE sha256 IS NOT NULL)",
        ).use { it.executeUpdate() }
    }

    private fun readSnapshot(rs: ResultSet) = Snapshot(
        id = rs.getString("id"),
        sessionId = SessionId(rs.getString("session_id")),
        path = rs.getString("path"),
        content = rs.getString("content") ?: "",
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

/**
 * Correlated subquery selecting the newest snapshot id for every
 * `(session_id, path)`. Embedded in [SqliteSnapshotStore.pruneBounded]'s deletes
 * so those rows can never be evicted, which keeps a per-file revert possible.
 */
private const val PINNED_IDS =
    "(SELECT s.id AS id FROM snapshots s WHERE NOT EXISTS (" +
        "SELECT 1 FROM snapshots x WHERE x.session_id = s.session_id AND x.path = s.path " +
        "AND (x.created_at > s.created_at OR (x.created_at = s.created_at AND x.rowid > s.rowid))))"

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
    SnapshotMigration(3) { connection ->
        // Move bodies into a content-addressed `snapshot_blobs` table so the same
        // content is stored once. `snapshots` keeps its columns minus `content`;
        // explicit rowids are copied so created_at/rowid ordering is unchanged.
        connection.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS snapshot_blobs (" +
                    "sha256 TEXT PRIMARY KEY, content TEXT NOT NULL)",
            )
            val columns = st.executeQuery("PRAGMA table_info(snapshots)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
            // A DB already migrated to v3 has no content column to backfill from.
            if ("content" in columns) {
                // Key each row's blob by sha256, falling back to the row id for a
                // legacy row with no hash, so no body is dropped by the rebuild.
                st.execute(
                    "INSERT OR IGNORE INTO snapshot_blobs(sha256, content) " +
                        "SELECT CASE WHEN sha256 IS NULL OR sha256 = '' THEN id ELSE sha256 END, content " +
                        "FROM snapshots WHERE content IS NOT NULL",
                )
                st.execute(
                    "CREATE TABLE snapshots_new (" +
                        "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, path TEXT NOT NULL, " +
                        "sha256 TEXT NOT NULL, created_at INTEGER NOT NULL)",
                )
                st.execute(
                    "INSERT INTO snapshots_new(rowid, id, session_id, path, sha256, created_at) " +
                        "SELECT rowid, id, session_id, path, " +
                        "CASE WHEN sha256 IS NULL OR sha256 = '' THEN id ELSE sha256 END, created_at " +
                        "FROM snapshots",
                )
                st.execute("DROP TABLE snapshots")
                st.execute("ALTER TABLE snapshots_new RENAME TO snapshots")
                st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_session_path ON snapshots(session_id, path)")
            }
        }
    },
)
