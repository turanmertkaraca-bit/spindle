package dev.lumen.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import dev.spindle.core.store.SnapshotStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable [SnapshotStore] on Android's own SQLite, sharing `lumen.db` with
 * [AndroidSessionStore]. Snapshots are whole-file copies taken before a
 * mutating tool runs, so the agent's changes can be reverted on device.
 */
class AndroidSnapshotStore(context: Context) : SnapshotStore, AutoCloseable {

    private val db: SQLiteDatabase =
        context.applicationContext.openOrCreateDatabase("lumen.db", Context.MODE_PRIVATE, null)

    private val mutex = Mutex()

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS snapshots(
                 id TEXT PRIMARY KEY, session_id TEXT, path TEXT, content TEXT,
                 sha256 TEXT, created_at INTEGER)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snap ON snapshots(session_id, path)")
    }

    override suspend fun record(snapshot: Snapshot) = mutex.withLock {
        db.execSQL(
            "INSERT OR REPLACE INTO snapshots VALUES(?,?,?,?,?,?)",
            arrayOf(
                snapshot.id, snapshot.sessionId.value, snapshot.path,
                snapshot.content, snapshot.sha256, snapshot.createdAt,
            ),
        )
    }

    override suspend fun latest(sessionId: SessionId, path: String): Snapshot? = mutex.withLock {
        db.rawQuery(
            "SELECT * FROM snapshots WHERE session_id=? AND path=? ORDER BY created_at DESC, rowid DESC LIMIT 1",
            arrayOf(sessionId.value, path),
        ).use { c -> if (c.moveToFirst()) c.toSnapshot() else null }
    }

    override suspend fun forSession(sessionId: SessionId): List<Snapshot> = mutex.withLock {
        db.rawQuery(
            "SELECT * FROM snapshots WHERE session_id=? ORDER BY created_at, rowid",
            arrayOf(sessionId.value),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSnapshot()) } }
    }

    override suspend fun delete(ids: List<String>) = mutex.withLock {
        for (id in ids) db.delete("snapshots", "id=?", arrayOf(id))
    }

    override suspend fun prune(keep: Int): Int = mutex.withLock {
        if (keep < 0) return@withLock 0
        val sessions = db.rawQuery("SELECT DISTINCT session_id FROM snapshots", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        var removed = 0
        for (sid in sessions) {
            val ids = db.rawQuery(
                "SELECT id FROM snapshots WHERE session_id=? ORDER BY created_at DESC, rowid DESC",
                arrayOf(sid),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            ids.drop(keep).forEach { db.delete("snapshots", "id=?", arrayOf(it)); removed++ }
        }
        removed
    }

    override fun close() {
        runCatching { db.close() }
    }

    private fun Cursor.toSnapshot(): Snapshot = Snapshot(
        id = getString(getColumnIndexOrThrow("id")),
        sessionId = SessionId(getString(getColumnIndexOrThrow("session_id"))),
        path = getString(getColumnIndexOrThrow("path")),
        content = getString(getColumnIndexOrThrow("content")) ?: "",
        sha256 = getString(getColumnIndexOrThrow("sha256")) ?: "",
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
    )
}
