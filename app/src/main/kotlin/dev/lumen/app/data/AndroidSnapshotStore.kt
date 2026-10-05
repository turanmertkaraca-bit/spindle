package dev.lumen.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import dev.spindle.core.store.SnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Durable [SnapshotStore] on Android's own SQLite, sharing `lumen.db` with
 * [AndroidSessionStore] through one lazily-opened [AndroidDatabase]. Snapshots
 * are whole-file copies taken before a mutating tool runs, so the agent's
 * changes can be reverted on device.
 *
 * All methods hop to [Dispatchers.IO] before touching SQLite.
 */
class AndroidSnapshotStore internal constructor(private val shared: AndroidDatabase) :
    SnapshotStore, AutoCloseable {

    constructor(context: Context) : this(AndroidDatabase(context))

    private val mutex = Mutex()

    private val db: SQLiteDatabase get() = shared.db()

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    override suspend fun record(snapshot: Snapshot) = locked {
        db.execSQL(
            "INSERT OR REPLACE INTO snapshots VALUES(?,?,?,?,?,?)",
            arrayOf(
                snapshot.id, snapshot.sessionId.value, snapshot.path,
                snapshot.content, snapshot.sha256, snapshot.createdAt,
            ),
        )
    }

    override suspend fun latest(sessionId: SessionId, path: String): Snapshot? = locked {
        db.rawQuery(
            "SELECT * FROM snapshots WHERE session_id=? AND path=? ORDER BY created_at DESC, rowid DESC LIMIT 1",
            arrayOf(sessionId.value, path),
        ).use { c -> if (c.moveToFirst()) c.toSnapshot() else null }
    }

    override suspend fun forSession(sessionId: SessionId): List<Snapshot> = locked {
        db.rawQuery(
            "SELECT * FROM snapshots WHERE session_id=? ORDER BY created_at, rowid",
            arrayOf(sessionId.value),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSnapshot()) } }
    }

    override suspend fun delete(ids: List<String>) = locked {
        if (ids.isEmpty()) return@locked
        db.beginTransaction()
        try {
            for (id in ids) db.delete("snapshots", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun prune(keep: Int): Int = locked {
        if (keep < 0) return@locked 0
        var removed = 0
        db.beginTransaction()
        try {
            val sessions = db.rawQuery("SELECT DISTINCT session_id FROM snapshots", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            for (sid in sessions) {
                val ids = db.rawQuery(
                    "SELECT id FROM snapshots WHERE session_id=? ORDER BY created_at DESC, rowid DESC",
                    arrayOf(sid),
                ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                ids.drop(keep).forEach { db.delete("snapshots", "id=?", arrayOf(it)); removed++ }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        removed
    }

    /**
     * Bounded retention across every session. Age is applied first, then the
     * per-session cap, then the global cap, all in one transaction so a long
     * session cannot exhaust storage with whole-file snapshots.
     */
    override suspend fun pruneBounded(keepPerSession: Int, maxTotal: Int, maxAgeMillis: Long): Int = locked {
        var removed = 0
        db.beginTransaction()
        try {
            if (maxAgeMillis > 0) {
                val cutoff = System.currentTimeMillis() - maxAgeMillis
                removed += db.delete("snapshots", "created_at < ?", arrayOf(cutoff.toString()))
            }
            if (keepPerSession >= 0) {
                val sessions = db.rawQuery("SELECT DISTINCT session_id FROM snapshots", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
                for (sid in sessions) {
                    val ids = db.rawQuery(
                        "SELECT id FROM snapshots WHERE session_id=? ORDER BY created_at DESC, rowid DESC",
                        arrayOf(sid),
                    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    ids.drop(keepPerSession).forEach { db.delete("snapshots", "id=?", arrayOf(it)); removed++ }
                }
            } else {
                removed += db.delete("snapshots", null, null)
            }
            if (maxTotal >= 0) {
                val count = db.rawQuery("SELECT COUNT(*) FROM snapshots", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                val excess = count - maxTotal
                if (excess > 0) {
                    val ids = db.rawQuery(
                        "SELECT id FROM snapshots ORDER BY created_at ASC, rowid ASC LIMIT ?",
                        arrayOf(excess.toString()),
                    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    ids.forEach { db.delete("snapshots", "id=?", arrayOf(it)); removed++ }
                }
            } else if (maxTotal < 0) {
                removed += db.delete("snapshots", null, null)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        removed
    }

    override fun close() {
        // Guard with the mutex so a close cannot race an in-flight worker query.
        kotlinx.coroutines.runBlocking {
            mutex.withLock { shared.close() }
        }
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
