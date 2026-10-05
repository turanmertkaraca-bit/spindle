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
     * The newest snapshot for every `(sessionId, path)` pair is *pinned*: it
     * survives the age, per-session and global caps, so a per-file revert always
     * has a pre-image as long as the path was ever snapshotted. Pins may leave
     * more than [keepPerSession] snapshots behind (correctness of revert wins
     * over a slightly larger bound). A negative [keepPerSession] or [maxTotal]
     * still means "drop everything", pins included.
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
            // Negative caps are the legacy "drop everything" sentinel and
            // deliberately ignore the pins below.
            if (keepPerSession < 0 || maxTotal < 0) {
                val before = items.size
                items.clear()
                return before
            }
            val removeIds = HashSet<String>()
            val now = System.currentTimeMillis()
            // Pin the newest snapshot of every (session, path) so a revert always
            // has a pre-image, even when the age/per-session/global caps would
            // otherwise evict it.
            val pinned = items.groupBy { it.sessionId to it.path }
                .values
                .mapTo(HashSet()) { group -> group.sortedBy { it.createdAt }.last().id }
            if (maxAgeMillis > 0) {
                items.filter { now - it.createdAt > maxAgeMillis && it.id !in pinned }
                    .forEach { removeIds += it.id }
            }
            val perSession = keepPerSession
            for ((_, snapshots) in items.filter { it.id !in removeIds }.groupBy { it.sessionId }) {
                val oldestFirst = snapshots.sortedBy { it.createdAt }
                oldestFirst.filter { it.id !in pinned }
                    .take((oldestFirst.size - perSession).coerceAtLeast(0))
                    .forEach { removeIds += it.id }
            }
            val survivors = items.filter { it.id !in removeIds }.sortedBy { it.createdAt }
            val globalCap = maxTotal
            val excess = survivors.size - globalCap
            if (excess > 0) {
                survivors.filter { it.id !in pinned }.take(excess).forEach { removeIds += it.id }
            }
            if (removeIds.isEmpty()) return 0
            val before = items.size
            items.removeAll { it.id in removeIds }
            return before - items.size
        }
    }
}
