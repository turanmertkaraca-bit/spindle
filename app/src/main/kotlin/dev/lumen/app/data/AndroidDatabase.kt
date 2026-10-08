package dev.lumen.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The one lazily-opened SQLite connection shared by [AndroidSessionStore] and
 * [AndroidSnapshotStore]. Opening also migrates the schema and installs the
 * connection pragmas. It is deliberately lazy: no disk I/O happens on the main
 * thread at Application construction, and every store operation reaches it from
 * [kotlinx.coroutines.Dispatchers.IO].
 *
 * The schema is versioned with `PRAGMA user_version`: each [Migration] runs in
 * its own transaction and only bumps the stored version on success, so a crash
 * mid-upgrade leaves the previous version intact rather than a half-migrated DB.
 */
internal class AndroidDatabase(context: Context, private val integrityOverride: ((SQLiteDatabase) -> Boolean)? = null) : AutoCloseable {

    private val appContext = context.applicationContext

    @Volatile
    private var database: SQLiteDatabase? = null

    /** Open (once) and return the shared connection. Safe from any thread. */
    fun db(): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            val opened = openHealthy()
            migrate(opened)
            checkpoint(opened)
            database = opened
            return opened
        }
    }

    /**
     * Open and validate the shared file. The integrity check runs once per open,
     * before any pragma that would read the schema. If opening or the check
     * fails, the file (and its WAL/SHM sidecars) is quarantined and an empty
     * database is created in its place, so a corrupt file degrades to lost local
     * history instead of a launch crash-loop.
     */
    private fun openHealthy(): SQLiteDatabase {
        val candidate = runCatching {
            appContext.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        }.getOrNull()
        // Wait out a transient writer lock before the check so a mere BUSY is not
        // mistaken for corruption.
        candidate?.let {
            runCatching { it.rawQuery("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS", null).use { c -> c.moveToFirst() } }
        }
        if (candidate != null && integrityOk(candidate)) {
            configure(candidate)
            return candidate
        }
        candidate?.let { runCatching { it.close() } }
        quarantine()
        val fresh = appContext.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        configure(fresh)
        return fresh
    }

    private fun configure(db: SQLiteDatabase) {
        // PRAGMA statements return rows, so they must go through rawQuery —
        // execSQL only accepts statements that produce no result set.
        db.rawQuery("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA foreign_keys=ON", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
        // NORMAL is the WAL-recommended durability/throughput point: a commit is
        // durable across app crashes, only a device power loss can lose the last
        // transaction. Truncating the WAL after open keeps the sidecar small.
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA cache_size=$CACHE_SIZE_KB", null).use { it.moveToFirst() }
    }

    /**
     * Versioned, transactional migrations. A fresh database reads `user_version
     * = 0` and replays every migration; an old one resumes at the first version
     * it has not applied. Each step commits atomically with its version bump.
     */
    private fun migrate(db: SQLiteDatabase) {
        val migrations = listOf(
            Migration(1) { createSchema(it) },
            Migration(2) { migrateMessagesUniqueSeq(it) },
            Migration(3) { migrateSnapshotSequence(it) },
        )
        val current = userVersion(db)
        for (migration in migrations) {
            if (migration.version <= current) continue
            db.beginTransaction()
            try {
                migration.applyTo(db)
                // The stored version commits atomically with the DDL above.
                db.rawQuery("PRAGMA user_version = ${migration.version}", null).use { it.moveToFirst() }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun userVersion(db: SQLiteDatabase): Int =
        db.rawQuery("PRAGMA user_version", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sessions(
                 id TEXT PRIMARY KEY, title TEXT, cwd TEXT, created_at INTEGER,
                 updated_at INTEGER, model TEXT, provider_id TEXT, agent TEXT, parent_id TEXT,
                 state TEXT, pinned INTEGER, archived INTEGER, tags TEXT)""",
        )
        // Only migrate after the CREATE above, so a first run cannot read
        // PRAGMA table_info on a table that does not exist yet.
        migrateSessions(db)
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS messages(
                 id TEXT PRIMARY KEY, session_id TEXT, role TEXT, created_at INTEGER,
                 model TEXT, provider_id TEXT, agent TEXT, usage TEXT, finish TEXT,
                 error TEXT, seq INTEGER)""",
        )
        // Non-unique here; migration 2 replaces it with a UNIQUE index once any
        // legacy duplicate seq values have been de-duplicated.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg ON messages(session_id, seq)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS parts(
                 id TEXT PRIMARY KEY, message_id TEXT, session_id TEXT, ord INTEGER, data TEXT)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_part ON parts(message_id, ord)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS todos(
                 id TEXT PRIMARY KEY, session_id TEXT, ord INTEGER, content TEXT, status TEXT)""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS snapshots(
                 id TEXT PRIMARY KEY, session_id TEXT, path TEXT, content TEXT,
                 sha256 TEXT, created_at INTEGER)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snap ON snapshots(session_id, path)")
    }

    /**
     * Add the columns introduced after the first schema, so old DBs migrate
     * forward. Runs after the `CREATE TABLE IF NOT EXISTS sessions` above, so the
     * table always exists when `PRAGMA table_info` is read.
     */
    private fun migrateSessions(db: SQLiteDatabase) {
        val existing = columnNames(db, "sessions")
        fun add(name: String, ddl: String) {
            if (name !in existing) db.execSQL("ALTER TABLE sessions ADD COLUMN $name $ddl")
        }
        add("state", "TEXT")
        add("pinned", "INTEGER")
        add("archived", "INTEGER")
        add("tags", "TEXT")
    }

    /**
     * Give `messages(session_id, seq)` a real uniqueness guarantee, so a
     * concurrent writer can never persist two rows in the same slot.
     *
     * The de-dup first renumbers every message to a gap-free sequence per
     * session, preserving the existing `seq` order (ties broken by `rowid`). The
     * mapping is materialised in a temporary table because a correlated `UPDATE`
     * that reads `seq` while rewriting it would observe its own partial writes
     * and could reorder rows.
     */
    private fun migrateMessagesUniqueSeq(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS _message_seq")
        db.execSQL(
            """CREATE TEMP TABLE _message_seq AS
               SELECT m.id AS mid,
                 (SELECT COUNT(*) FROM messages x
                  WHERE x.session_id = m.session_id
                    AND (COALESCE(x.seq, 0) < COALESCE(m.seq, 0)
                         OR (COALESCE(x.seq, 0) = COALESCE(m.seq, 0) AND x.rowid <= m.rowid))) AS new_seq
               FROM messages m""",
        )
        db.execSQL(
            "UPDATE messages SET seq = (SELECT new_seq FROM _message_seq WHERE mid = messages.id)",
        )
        db.execSQL("DROP TABLE _message_seq")
        db.execSQL("DROP INDEX IF EXISTS idx_msg")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg ON messages(session_id, seq)")
    }

    /**
     * Add a monotonic `sequence` to `snapshots` so "newest" no longer depends on
     * the (rollback-prone) wall clock. SQLite only treats `INTEGER PRIMARY KEY`
     * as an AUTOINCREMENT rowid, so the table is rebuilt rather than altered.
     */
    private fun migrateSnapshotSequence(db: SQLiteDatabase) {
        if ("sequence" in columnNames(db, "snapshots")) return
        db.execSQL(
            """CREATE TABLE snapshots_new(
                 id TEXT NOT NULL UNIQUE, session_id TEXT, path TEXT, content TEXT,
                 sha256 TEXT, created_at INTEGER, sequence INTEGER PRIMARY KEY AUTOINCREMENT)""",
        )
        db.execSQL(
            """INSERT INTO snapshots_new(id, session_id, path, content, sha256, created_at, sequence)
               SELECT id, session_id, path, content, sha256, created_at, rowid
               FROM snapshots ORDER BY created_at, rowid""",
        )
        db.execSQL("DROP TABLE snapshots")
        db.execSQL("ALTER TABLE snapshots_new RENAME TO snapshots")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snap ON snapshots(session_id, path)")
    }

    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet {
                val name = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) add(c.getString(name))
            }
        }

    /** Delegates to [integrityOverride] when supplied, otherwise the real check. */
    private fun integrityOk(db: SQLiteDatabase): Boolean = integrityOverride?.invoke(db) ?: quickCheckOk(db)

    /**
     * A cheap read that walks the pages without verifying indexes. A healthy
     * database answers with a single `ok`; anything else (an error string, extra
     * rows, or a thrown exception) means we should not trust the file.
     */
    private fun quickCheckOk(db: SQLiteDatabase): Boolean = runCatching {
        db.rawQuery("PRAGMA quick_check", null).use { c ->
            c.moveToFirst() && c.count == 1 && "ok".equals(c.getString(0), ignoreCase = true)
        }
    }.getOrDefault(false)

    /** Rename `lumen.db` and its WAL/SHM sidecars to `.corrupt-<ts>` siblings. */
    private fun quarantine() {
        val stamp = System.currentTimeMillis()
        val base = appContext.getDatabasePath(DB_NAME)
        for (suffix in listOf("", "-wal", "-shm")) {
            val file = File(base.path + suffix)
            if (file.exists()) {
                runCatching { file.renameTo(File(file.path + ".corrupt-$stamp")) }
            }
        }
    }

    private fun checkpoint(db: SQLiteDatabase) {
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
    }

    override fun close() {
        synchronized(this) {
            database?.let { runCatching { it.close() } }
            database = null
        }
    }

    private companion object {
        const val DB_NAME = "lumen.db"
        const val BUSY_TIMEOUT_MS = 5_000

        /** Negative is KiB, so this is a 64 MiB page cache. */
        const val CACHE_SIZE_KB = -64_000
    }
}

/** One ordered, transactional schema step; see [AndroidDatabase.migrate]. */
private class Migration(val version: Int, val applyTo: (SQLiteDatabase) -> Unit)
