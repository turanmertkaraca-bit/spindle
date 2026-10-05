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

    /**
     * Bounded retention across every session, meant to run at a safe boundary
     * such as the end of an agent run. Keeps at most [keepPerSession] newest
     * snapshots per session, at most [maxTotal] newest overall, and drops
     * anything older than [maxAgeMillis]. Returns the number of rows removed.
     *
     * The default only enforces [keepPerSession] (via [prune]); durable stores
     * that can enumerate every session should override this to also bound the
     * global count and the age, so snapshots cannot grow forever.
     */
    suspend fun pruneBounded(
        keepPerSession: Int = DEFAULT_KEEP_PER_SESSION,
        maxTotal: Int = DEFAULT_MAX_TOTAL,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): Int = prune(keepPerSession)

    companion object {
        /** Newest snapshots retained per session by [pruneBounded]. */
        const val DEFAULT_KEEP_PER_SESSION = 200

        /** Newest snapshots retained across all sessions by [pruneBounded]. */
        const val DEFAULT_MAX_TOTAL = 2_000

        /** Snapshots older than this (7 days) are dropped by [pruneBounded]. */
        const val DEFAULT_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
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

    override suspend fun pruneBounded(keepPerSession: Int, maxTotal: Int, maxAgeMillis: Long): Int {
        synchronized(lock) {
            val removeIds = HashSet<String>()
            val now = System.currentTimeMillis()
            if (maxAgeMillis > 0) {
                items.filter { now - it.createdAt > maxAgeMillis }.forEach { removeIds += it.id }
            }
            val perSession = keepPerSession.coerceAtLeast(0)
            for ((_, snapshots) in items.filter { it.id !in removeIds }.groupBy { it.sessionId }) {
                if (snapshots.size <= perSession) continue
                snapshots.sortedBy { it.createdAt }
                    .take(snapshots.size - perSession)
                    .forEach { removeIds += it.id }
            }
            val survivors = items.filter { it.id !in removeIds }.sortedBy { it.createdAt }
            val globalCap = maxTotal.coerceAtLeast(0)
            if (globalCap < survivors.size) {
                survivors.take(survivors.size - globalCap).forEach { removeIds += it.id }
            }
            if (removeIds.isEmpty()) return 0
            val before = items.size
            items.removeAll { it.id in removeIds }
            return before - items.size
        }
    }
}
