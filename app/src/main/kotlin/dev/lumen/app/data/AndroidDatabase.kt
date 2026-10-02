package dev.lumen.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * The one lazily-opened SQLite connection shared by [AndroidSessionStore] and
 * [AndroidSnapshotStore]. Opening also migrates the schema and installs the
 * connection pragmas. It is deliberately lazy: no disk I/O happens on the main
 * thread at Application construction, and every store operation reaches it from
 * [kotlinx.coroutines.Dispatchers.IO].
 */
internal class AndroidDatabase(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext

    @Volatile
    private var database: SQLiteDatabase? = null

    /** Open (once) and return the shared connection. Safe from any thread. */
    fun db(): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            val opened = appContext.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
            configure(opened)
            createSchema(opened)
            database = opened
            return opened
        }
    }

    private fun configure(db: SQLiteDatabase) {
        // PRAGMA statements return rows, so they must go through rawQuery —
        // execSQL only accepts statements that produce no result set.
        db.rawQuery("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA foreign_keys=ON", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
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

    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet {
                val name = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) add(c.getString(name))
            }
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
    }
}
