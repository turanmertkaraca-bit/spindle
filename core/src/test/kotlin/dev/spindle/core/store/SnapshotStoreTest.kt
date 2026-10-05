package dev.spindle.core.store

import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotStoreTest {

    private fun snap(session: String, id: String, createdAt: Long) = Snapshot(
        id = id,
        sessionId = SessionId(session),
        path = "a.kt",
        content = "c",
        sha256 = "h",
        createdAt = createdAt,
    )

    @Test
    fun `prune caps each session independently and is bounds safe`() = runTest {
        val store = InMemorySnapshotStore()
        store.record(snap("s1", "s1a", 1))
        store.record(snap("s1", "s1b", 2))
        store.record(snap("s1", "s1c", 3))
        store.record(snap("s2", "s2a", 1))
        store.record(snap("s2", "s2b", 2))

        assertEquals(1, store.prune(2))
        assertEquals(listOf("s1b", "s1c"), store.forSession(SessionId("s1")).map { it.id })
        assertEquals(listOf("s2a", "s2b"), store.forSession(SessionId("s2")).map { it.id })

        assertEquals(4, store.prune(0))
        assertEquals(emptyList(), store.forSession(SessionId("s1")))
        assertEquals(0, store.prune(-5))
    }

    @Test
    fun `pruneBounded caps per session, global count and age together`() = runTest {
        val store = InMemorySnapshotStore()
        val now = System.currentTimeMillis()
        store.record(snap("s1", "a1", now - 100))
        store.record(snap("s1", "a2", now - 90))
        store.record(snap("s1", "a3", now - 80))
        store.record(snap("s2", "b1", now - 70))
        store.record(snap("s2", "b2", now - 60))
        store.record(snap("s3", "old", now - 10L * 24 * 60 * 60 * 1_000))

        val removed = store.pruneBounded(
            keepPerSession = 2,
            maxTotal = 4,
            maxAgeMillis = 24L * 60 * 60 * 1_000,
        )

        assertEquals(2, removed, "the aged snapshot and s1's oldest should go")
        assertEquals(listOf("a2", "a3"), store.forSession(SessionId("s1")).map { it.id })
        assertEquals(listOf("b1", "b2"), store.forSession(SessionId("s2")).map { it.id })
        assertEquals(emptyList(), store.forSession(SessionId("s3")))
    }

    @Test
    fun `pruneBounded enforces the global cap newest first`() = runTest {
        val store = InMemorySnapshotStore()
        val now = System.currentTimeMillis()
        (0 until 10).forEach { i -> store.record(snap("one", "s$i", now + i)) }

        val removed = store.pruneBounded(keepPerSession = 100, maxTotal = 3, maxAgeMillis = 0)

        assertEquals(7, removed)
        assertEquals(listOf("s7", "s8", "s9"), store.forSession(SessionId("one")).map { it.id })
    }

    @Test
    fun `pruneBounded default delegates to prune with the per-session keep`() = runTest {
        // A store that only implements [SnapshotStore.prune] must still get a
        // working pruneBounded: the interface default forwards keepPerSession.
        val store = PruneOnlyStore()

        val removed = store.pruneBounded(keepPerSession = 42)

        assertEquals(7, removed)
        assertEquals(42, store.prunedWith)
        assertEquals(1, store.pruneCalls)
    }

    @Test
    fun `a negative maxTotal drops every snapshot`() = runTest {
        val store = InMemorySnapshotStore()
        store.record(snap("s1", "a", 1))
        store.record(snap("s2", "b", 2))

        val removed = store.pruneBounded(keepPerSession = 100, maxTotal = -1, maxAgeMillis = 0)

        assertEquals(2, removed)
    }

    /** Minimal store that omits the [SnapshotStore.pruneBounded] override. */
    private class PruneOnlyStore : SnapshotStore {
        var prunedWith = -1
        var pruneCalls = 0

        override suspend fun record(snapshot: Snapshot) = Unit
        override suspend fun latest(sessionId: SessionId, path: String): Snapshot? = null
        override suspend fun forSession(sessionId: SessionId): List<Snapshot> = emptyList()
        override suspend fun delete(ids: List<String>) = Unit
        override suspend fun prune(keep: Int): Int {
            prunedWith = keep
            pruneCalls++
            return 7
        }
    }
}
