package dev.spindle.core.store

import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotStoreTest {

    private fun snap(session: String, id: String, createdAt: Long) =
        snap(session, id, "a.kt", createdAt)

    private fun snap(session: String, id: String, path: String, createdAt: Long) = Snapshot(
        id = id,
        sessionId = SessionId(session),
        path = path,
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

        assertEquals(2, removed, "s1's oldest two go; the aged snapshot is pinned")
        assertEquals(listOf("a3"), store.forSession(SessionId("s1")).map { it.id })
        assertEquals(listOf("b1", "b2"), store.forSession(SessionId("s2")).map { it.id })
        assertEquals(listOf("old"), store.forSession(SessionId("s3")).map { it.id })
    }

    @Test
    fun `pruneBounded pins the newest snapshot per session and path`() = runTest {
        val store = InMemorySnapshotStore()
        val now = System.currentTimeMillis()
        // Two paths in one session plus a second session, so each (session, path)
        // pair has a newest pre-image the caps must not evict.
        store.record(snap("s1", "a1", "src/a.kt", now - 50))
        store.record(snap("s1", "a2", "src/a.kt", now - 40))
        store.record(snap("s1", "b1", "src/b.kt", now - 30))
        store.record(snap("s1", "b2", "src/b.kt", now - 20))
        store.record(snap("s2", "c1", "src/c.kt", now - 10))
        // Older than the age cap: pinned because it is s3's only pre-image.
        store.record(snap("s3", "old1", "src/old.kt", now - 10L * 24 * 60 * 60 * 1_000))

        // A zero global cap and a tiny age would previously wipe everything.
        val removed = store.pruneBounded(
            keepPerSession = 0,
            maxTotal = 0,
            maxAgeMillis = 24L * 60 * 60 * 1_000,
        )

        // The newest snapshot of every path survives, even s3's aged one.
        assertEquals("a2", store.latest(SessionId("s1"), "src/a.kt")?.id)
        assertEquals("b2", store.latest(SessionId("s1"), "src/b.kt")?.id)
        assertEquals("c1", store.latest(SessionId("s2"), "src/c.kt")?.id)
        assertEquals("old1", store.latest(SessionId("s3"), "src/old.kt")?.id)
        // Older pre-images beyond the caps are gone.
        assertEquals(2, removed)
        assertEquals(listOf("a2", "b2"), store.forSession(SessionId("s1")).map { it.id })
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

    @Test
    fun `a negative keepPerSession drops every snapshot including pins`() = runTest {
        val store = InMemorySnapshotStore()
        // One pin per (session, path); the sentinel must ignore them.
        store.record(snap("s1", "a", "a.kt", 1))
        store.record(snap("s1", "b", "b.kt", 2))
        store.record(snap("s2", "c", "c.kt", 3))

        val removed = store.pruneBounded(keepPerSession = -1, maxTotal = 100, maxAgeMillis = 0)

        assertEquals(3, removed)
        assertEquals(emptyList(), store.forSession(SessionId("s1")))
        assertEquals(emptyList(), store.forSession(SessionId("s2")))
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
