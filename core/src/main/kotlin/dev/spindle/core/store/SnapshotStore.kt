package dev.spindle.core.store

import dev.spindle.core.model.Snapshot
import dev.spindle.core.model.SessionId

/**
 * Durable store for pre-edit file snapshots, so any change the agent makes can
 * be reverted (per file, per run, or per session). Kept separate from
 * [SessionStore] because snapshots are large and have a different lifecycle.
 */
interface SnapshotStore {
    suspend fun record(snapshot: Snapshot)

    /** The most recent snapshot of [path] in [sessionId], or null if never recorded. */
    suspend fun latest(sessionId: SessionId, path: String): Snapshot?

    suspend fun forSession(sessionId: SessionId): List<Snapshot>

    suspend fun delete(ids: List<String>)

    /** Keep at most the newest [keep] snapshots per session. Returns removed count. */
    suspend fun prune(keep: Int = 500): Int
}

/** Default in-memory implementation for tests and headless hosts. */
class InMemorySnapshotStore : SnapshotStore {
    private val lock = Any()
    private val items = ArrayList<Snapshot>()

    override suspend fun record(snapshot: Snapshot) {
        synchronized(lock) { items += snapshot }
    }

    override suspend fun latest(sessionId: SessionId, path: String): Snapshot? =
        synchronized(lock) {
            items.lastOrNull { it.sessionId == sessionId && it.path == path }
        }

    override suspend fun forSession(sessionId: SessionId): List<Snapshot> =
        synchronized(lock) { items.filter { it.sessionId == sessionId } }

    override suspend fun delete(ids: List<String>) {
        val set = ids.toSet()
        synchronized(lock) { items.removeAll { it.id in set } }
    }

    override suspend fun prune(keep: Int): Int {
        val cap = keep.coerceAtLeast(0)
        synchronized(lock) {
            val removeIds = HashSet<String>()
            for ((_, snapshots) in items.groupBy { it.sessionId }) {
                if (snapshots.size <= cap) continue
                snapshots.sortedBy { it.createdAt }
                    .take(snapshots.size - cap)
                    .forEach { removeIds += it.id }
            }
            if (removeIds.isEmpty()) return 0
            val before = items.size
            items.removeAll { it.id in removeIds }
            return before - items.size
        }
    }
}
